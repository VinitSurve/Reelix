"""Pipeline coordinator for executing multi-stage cloud processing jobs."""

from __future__ import annotations

import logging
import random
import shutil
import threading
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable, Dict, Optional

from .config import WorkerConfig
from .models import (
    ArtifactRef,
    JobManifest,
    JobStatus,
    MediaInfo,
    StageStatus,
    TransitionRecord,
    TransitionType,
    compute_file_sha256,
    fold_transitions,
)
from .stages.audio_extractor import FFmpegAudioExtractor
from .stages.downloader import DownloadStatus, MediaDownloader, YtDlpDownloader
from .stages.gemini_analyzer import GeminiAnalyzer
from .stages.ocr_extractor import VisualTextExtractor
from .stages.transcriber import WhisperTranscriber
from .storage import StorageClient

logger = logging.getLogger(__name__)


class ContinuousHeartbeatManager:
    """Manages continuous lease renewal in a daemon background thread throughout long operations."""

    def __init__(
        self,
        storage: StorageClient,
        job: JobManifest,
        runner_id: str,
        lease_timeout_ms: int = 900_000,
        interval_seconds: float = 30.0,
        coordinator: Optional[Any] = None,
        clock_func: Optional[Callable[[], float]] = None,
    ):
        self.storage = storage
        self.job = job
        self.runner_id = runner_id
        self.lease_timeout_ms = lease_timeout_ms
        self.interval_seconds = interval_seconds
        self.coordinator = coordinator
        self.clock_func = clock_func or time.time
        self.stop_event = threading.Event()
        self.cancelled_event = threading.Event()
        self.last_renewal_ts: float = self.clock_func()
        self.renewal_failures: int = 0
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()

    def start(self) -> None:
        self.stop_event.clear()
        self.cancelled_event.clear()
        self._thread = threading.Thread(
            target=self._run_loop,
            name=f"Heartbeat-{self.job.item_id[:8]}",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self.stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2.0)

    @property
    def is_cancelled(self) -> bool:
        return self.cancelled_event.is_set()

    def renew_now(self) -> bool:
        """Explicit synchronously invoked renewal decoupled from disposable projection."""
        with self._lock:
            if self.is_cancelled:
                return False

            # Authoritative check: verify worker authority in append-only transition log
            try:
                transitions = self.storage.list_transitions(self.job.item_id)
                for t in transitions:
                    if t.generation > self.job.generation or (
                        t.generation == self.job.generation
                        and t.attempt_epoch > (self.job.active_attempt_epoch or 1)
                    ):
                        logger.warning(
                            "Worker fenced out for job %s: newer transition found (gen=%d, epoch=%d) > active (gen=%d, epoch=%d)",
                            self.job.item_id,
                            t.generation,
                            t.attempt_epoch,
                            self.job.generation,
                            self.job.active_attempt_epoch or 1,
                        )
                        self.cancelled_event.set()
                        return False
            except Exception as e:
                logger.warning("Could not list transitions for lease authority check on %s: %s", self.job.item_id, e)

            # Check storage lease renewal
            success = True
            if self.coordinator:
                success = self.coordinator.renew_lease(
                    self.job.item_id,
                    self.runner_id,
                    self.lease_timeout_ms,
                )
            if success:
                success = self.storage.renew_lease(
                    self.job.item_id,
                    self.runner_id,
                    self.lease_timeout_ms,
                    job=self.job,
                )
            if not success:
                logger.warning(
                    "Heartbeat lease renewal failed for job %s! Lease stolen, expired, or cancelled.",
                    self.job.item_id,
                )
                self.cancelled_event.set()
                return False

            # Emit authoritative HEARTBEAT transition if elapsed time >= 5 minutes
            now = self.clock_func()
            now_ms = int(now * 1000)
            if (now - self.last_renewal_ts) >= 300.0:
                try:
                    hb_seq = getattr(self.job, "_heartbeat_seq", 100) + 1
                    setattr(self.job, "_heartbeat_seq", hb_seq)
                    hb_trans = TransitionRecord(
                        item_id=self.job.item_id,
                        generation=self.job.generation,
                        attempt_epoch=self.job.active_attempt_epoch or 1,
                        transition_seq=hb_seq,
                        transition_type=TransitionType.HEARTBEAT,
                        attempt_id=self.runner_id,
                        created_at=now_ms,
                        payload={"lease_timeout_ms": self.lease_timeout_ms},
                    )
                    self.storage.append_transition(hb_trans)
                except Exception as hb_err:
                    logger.warning("Failed appending authoritative HEARTBEAT transition for %s: %s", self.job.item_id, hb_err)

            self.last_renewal_ts = now
            return True

    def _run_loop(self) -> None:
        while not self.stop_event.is_set():
            if self.stop_event.wait(timeout=self.interval_seconds):
                break
            if not self.renew_now():
                logger.error(
                    "Background heartbeat renewal failed for job %s! Triggering pipeline cancellation.",
                    self.job.item_id,
                )
                break

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.stop()


@dataclass
class BatchRunSummary:
    total_found: int = 0
    processed: int = 0
    succeeded: int = 0
    failed: int = 0


class CloudProcessingPipeline:
    """Orchestrates job claiming, media acquisition, audio extraction, Whisper transcription, and Gemini analysis."""

    def __init__(
        self,
        config: WorkerConfig,
        storage: StorageClient,
        coordinator: Optional[Any] = None,
        audio_extractor: Optional[FFmpegAudioExtractor] = None,
        transcriber: Optional[WhisperTranscriber] = None,
        gemini_analyzer: Optional[GeminiAnalyzer] = None,
        downloader: Optional[MediaDownloader] = None,
        ocr_extractor: Optional[VisualTextExtractor] = None,
    ):
        self.config = config
        self.storage = storage
        self.coordinator = coordinator
        self.audio_extractor = audio_extractor or FFmpegAudioExtractor()
        self.transcriber = transcriber or WhisperTranscriber(model_name=config.whisper_model)
        self.gemini_analyzer = gemini_analyzer or GeminiAnalyzer(
            api_key=config.gemini_api_key,
            model=config.gemini_model,
        )
        self.downloader = downloader or YtDlpDownloader()
        self.ocr_extractor = ocr_extractor or VisualTextExtractor()
        self._current_heartbeat: Optional[ContinuousHeartbeatManager] = None
        self._attempt_seq: Dict[str, int] = {}
        self._last_transition_id: Dict[str, str] = {}
        self._last_transition_hash: Dict[str, str] = {}

    def _next_seq(self, item_id: str) -> int:
        cur = self._attempt_seq.get(item_id, 0) + 1
        self._attempt_seq[item_id] = cur
        return cur

    def _is_cancelled(self, job: Optional[JobManifest] = None) -> bool:
        """Returns True if the active worker lease has been cancelled, lost, or expired."""
        return bool(self._current_heartbeat and self._current_heartbeat.is_cancelled)

    def _heartbeat(self, job: JobManifest) -> bool:
        """Sends a heartbeat to renew the distributed lease during long-running tasks."""
        if self._current_heartbeat:
            if self._current_heartbeat.is_cancelled:
                return False
            return self._current_heartbeat.renew_now()

        if self.coordinator:
            if not self.coordinator.renew_lease(
                job.item_id,
                self.config.runner_id,
                self.config.lease_timeout_ms,
            ):
                return False
        return self.storage.renew_lease(
            job.item_id,
            self.config.runner_id,
            self.config.lease_timeout_ms,
            job=job,
        )

    def _update_stage(
        self,
        job: JobManifest,
        stage: str,
        status: StageStatus,
        artifact: Optional[ArtifactRef] = None,
        error: Optional[str] = None,
    ) -> bool:
        """Atomically updates stage state in coordinator, logs transition, and persists manifest."""
        if self._is_cancelled(job):
            logger.warning("Rejecting stage update for %s on job %s: worker lease cancelled/lost", stage, job.item_id)
            return False

        # Authoritative fencing check before publication:
        # Check transition log to verify worker has not been superseded by a newer epoch or generation
        try:
            transitions = self.storage.list_transitions(job.item_id)
            for t in transitions:
                if t.generation > job.generation or (
                    t.generation == job.generation
                    and t.attempt_epoch > (job.active_attempt_epoch or 1)
                ):
                    logger.warning(
                        "Fenced out before publishing stage %s on %s: newer transition found (gen=%d, epoch=%d) > active (gen=%d, epoch=%d)",
                        stage,
                        job.item_id,
                        t.generation,
                        t.attempt_epoch,
                        job.generation,
                        job.active_attempt_epoch or 1,
                    )
                    if self._current_heartbeat:
                        self._current_heartbeat.cancelled_event.set()
                    return False
        except Exception as e:
            logger.warning("Could not list transitions for stage fencing check on %s: %s", job.item_id, e)

        if artifact:
            artifact.generation = job.generation
            artifact.attempt_epoch = job.active_attempt_epoch or 1

        job.record_stage_update(stage, status, error)
        if artifact:
            job.artifacts[stage] = artifact
        job.updated_at = int(time.time() * 1000)

        # Append stage transition record if succeeded or skipped
        stage_type_map = {
            "acquisition": TransitionType.ACQUIRE,
            "audio_extraction": TransitionType.AUDIO_EXTRACTED,
            "transcription": TransitionType.TRANSCRIBED,
            "ocr": TransitionType.OCR_EXTRACTED,
            "gemini_analysis": TransitionType.ANALYZED,
        }
        if status in (StageStatus.SUCCEEDED, StageStatus.SKIPPED) and stage in stage_type_map:
            ttype = stage_type_map[stage]
            payload: Dict[str, Any] = {"status": status.value}
            if artifact:
                payload["artifact"] = artifact.to_dict()
            if stage == "acquisition" and job.media:
                payload["media"] = asdict(job.media) if hasattr(job.media, "__dataclass_fields__") else {}
            elif stage == "transcription" and job.results.transcript:
                payload["transcript"] = job.results.transcript
                payload["transcript_model"] = job.results.transcript_model
            elif stage == "ocr" and job.results.ocr_text:
                payload["ocr_text"] = job.results.ocr_text
            elif stage == "gemini_analysis" and job.results.analysis:
                payload["analysis"] = asdict(job.results.analysis) if hasattr(job.results.analysis, "__dataclass_fields__") else {}

            seq = self._next_seq(job.item_id)
            prev_id = self._last_transition_id.get(job.item_id)
            prev_hash = self._last_transition_hash.get(job.item_id)
            trans = TransitionRecord(
                item_id=job.item_id,
                generation=job.generation,
                attempt_epoch=job.active_attempt_epoch or 1,
                transition_seq=seq,
                transition_type=ttype,
                attempt_id=self.config.runner_id,
                previous_transition_id=prev_id,
                previous_hash=prev_hash,
                payload=payload,
                created_at=job.updated_at,
            )
            self.storage.append_transition(trans)
            self._last_transition_id[job.item_id] = trans.deterministic_transition_id
            self._last_transition_hash[job.item_id] = trans.compute_transition_hash()
            job.projection_version = (job.projection_version or 1) + 1
            job.last_transition_id = trans.deterministic_transition_id
            job.last_transition_hash = trans.compute_transition_hash()

        if self.coordinator:
            if not self.coordinator.update_stage_progress(
                job_id=job.item_id,
                worker_id=self.config.runner_id,
                stage=stage,
                status=status,
                artifact=artifact,
                error=error,
            ):
                logger.warning("Lost distributed lease on job %s during stage %s update", job.item_id, stage)
                return False

        # Update disposable projection (non-fatal; authoritative state is recorded in transition log)
        try:
            self.storage.update_job(job)
        except Exception as e:
            logger.warning(
                "Failed to refresh projection jobs/%s.json after %s update: %s (authoritative transitions intact)",
                job.item_id,
                stage,
                e,
            )

        return True

    def _record_completion_transition(self, job: JobManifest) -> bool:
        """Appends a terminal COMPLETED transition record to immutable storage and updates projection fields."""
        # Fencing check before terminal completion
        try:
            transitions = self.storage.list_transitions(job.item_id)
            for t in transitions:
                if t.generation > job.generation or (
                    t.generation == job.generation
                    and t.attempt_epoch > (job.active_attempt_epoch or 1)
                ):
                    logger.warning(
                        "Fenced out before recording completion for %s: newer transition found (gen=%d, epoch=%d)",
                        job.item_id,
                        t.generation,
                        t.attempt_epoch,
                    )
                    return False
        except Exception as e:
            logger.warning("Could not list transitions for completion fencing check on %s: %s", job.item_id, e)

        seq = self._next_seq(job.item_id)
        comp_trans = TransitionRecord(
            item_id=job.item_id,
            generation=job.generation,
            attempt_epoch=job.active_attempt_epoch or 1,
            transition_seq=seq,
            transition_type=TransitionType.COMPLETED,
            attempt_id=self.config.runner_id,
            previous_transition_id=self._last_transition_id.get(job.item_id),
            previous_hash=self._last_transition_hash.get(job.item_id),
            payload={"completed_at": job.updated_at},
            created_at=job.updated_at,
        )
        self.storage.append_transition(comp_trans)
        self._last_transition_id[job.item_id] = comp_trans.deterministic_transition_id
        self._last_transition_hash[job.item_id] = comp_trans.compute_transition_hash()
        job.projection_version = (job.projection_version or 1) + 1
        job.last_transition_id = comp_trans.deterministic_transition_id
        job.last_transition_hash = comp_trans.compute_transition_hash()
        return True

    def run_batch(self) -> BatchRunSummary:
        """Finds and executes up to max_jobs_per_run pending jobs."""
        logger.info("Listing queued jobs (limit=%d)...", self.config.max_jobs_per_run)
        if self.coordinator:
            queued = self.coordinator.list_queued_jobs(limit=self.config.max_jobs_per_run)
        else:
            queued = self.storage.list_queued_jobs(limit=self.config.max_jobs_per_run)
        summary = BatchRunSummary(total_found=len(queued))

        logger.info("Found %d pending job(s) to process", len(queued))

        for job in queued:
            summary.processed += 1
            success = self.process_job(job)
            if success:
                summary.succeeded += 1
            else:
                summary.failed += 1

        logger.info(
            "Batch execution finished: %d succeeded, %d failed out of %d processed",
            summary.succeeded,
            summary.failed,
            summary.processed,
        )
        return summary

    def process_job(self, job: JobManifest) -> bool:
        """Processes a single job through all remaining uncompleted stages."""
        logger.info("========================================")
        logger.info("Claiming and processing job %s (source=%s)", job.item_id, job.source)
        logger.info("========================================")

        # 1. Fold existing transitions to reconstruct authoritative in-memory state before execution
        existing_transitions = self.storage.list_transitions(job.item_id)
        if existing_transitions:
            folded, warnings = fold_transitions(job.item_id, existing_transitions, base_manifest=job)
            for w in warnings:
                logger.warning("Transition log warning for %s: %s", job.item_id, w)
            if folded.status in (JobStatus.COMPLETED, JobStatus.FAILED):
                logger.info("Job %s is already settled (%s) according to transition log; skipping", job.item_id, folded.status.value)
                self.storage.update_job(folded)
                return False
            for k, v in folded.__dict__.items():
                setattr(job, k, v)

        now_ms = int(time.time() * 1000)
        gen_transitions = [t for t in existing_transitions if t.generation == job.generation]
        if gen_transitions:
            highest_epoch = max(t.attempt_epoch for t in gen_transitions)
            # Check if active epoch currently has an unexpired lease held by another runner
            latest_claim = next(
                (t for t in reversed(gen_transitions) if t.attempt_epoch == highest_epoch and t.transition_type == TransitionType.CLAIM),
                None,
            )
            if latest_claim:
                claimed_at = latest_claim.created_at
                timeout = latest_claim.payload.get("lease_timeout_ms", self.config.lease_timeout_ms)
                if (now_ms - claimed_at) < timeout and latest_claim.attempt_id != self.config.runner_id:
                    logger.warning(
                        "Job %s is actively claimed by %s (lease active for %d ms); skipping claim",
                        job.item_id,
                        latest_claim.attempt_id,
                        timeout - (now_ms - claimed_at),
                    )
                    return False
            active_epoch = highest_epoch + 1
        else:
            active_epoch = getattr(job, "active_attempt_epoch", 1) or 1

        job.active_attempt_epoch = active_epoch
        job.active_generation = job.generation

        now_ms = int(time.time() * 1000)
        job.status = JobStatus.PROCESSING
        job.claimed_by = self.config.runner_id
        job.claimed_at = now_ms
        job.updated_at = now_ms
        job.lease_timeout_ms = self.config.lease_timeout_ms

        if self.coordinator:
            claimed_manifest = self.coordinator.claim_job(
                job_id=job.item_id,
                worker_id=self.config.runner_id,
                lease_duration_ms=self.config.lease_timeout_ms,
            )
            if not claimed_manifest:
                logger.warning(
                    "Could not claim job %s in Firestore coordinator (already claimed or ineligible); skipping",
                    job.item_id,
                )
                return False
            job = claimed_manifest
            # Also sync claim to storage manifest
            if not self.storage.claim_job(job, runner_id=self.config.runner_id):
                logger.warning(
                    "Could not claim job %s in storage manifest (storage claim failed); skipping",
                    job.item_id,
                )
                return False
        else:
            if not self.storage.claim_job(job, runner_id=self.config.runner_id):
                logger.warning(
                    "Could not claim job %s (concurrent worker claimed it or lease still active); skipping",
                    job.item_id,
                )
                return False

        # Emit CLAIM transition record
        claim_seq = 1
        self._attempt_seq[job.item_id] = claim_seq
        prev_transition = existing_transitions[-1] if existing_transitions else None
        prev_id = prev_transition.deterministic_transition_id if prev_transition else None
        prev_hash = prev_transition.compute_transition_hash() if prev_transition else None
        claim_trans = TransitionRecord(
            item_id=job.item_id,
            generation=job.generation,
            attempt_epoch=active_epoch,
            transition_seq=claim_seq,
            transition_type=TransitionType.CLAIM,
            attempt_id=self.config.runner_id,
            previous_transition_id=prev_id,
            previous_hash=prev_hash,
            payload={"claimed_at": now_ms, "lease_timeout_ms": self.config.lease_timeout_ms},
            created_at=now_ms,
        )
        self.storage.append_transition(claim_trans)
        self._last_transition_id[job.item_id] = claim_trans.deterministic_transition_id
        self._last_transition_hash[job.item_id] = claim_trans.compute_transition_hash()
        job.projection_version = (job.projection_version or 1) + 1
        job.last_transition_id = claim_trans.deterministic_transition_id
        job.last_transition_hash = claim_trans.compute_transition_hash()

        job_dir = self.config.work_dir / f"{job.item_id}_gen{job.generation}_e{active_epoch}"
        job_dir.mkdir(parents=True, exist_ok=True)

        local_media_path: Optional[Path] = None
        local_wav_path = job_dir / f"{job.item_id}_gen{job.generation}_e{active_epoch}_audio.wav"

        with ContinuousHeartbeatManager(
            storage=self.storage,
            job=job,
            runner_id=self.config.runner_id,
            lease_timeout_ms=self.config.lease_timeout_ms,
            interval_seconds=self.config.heartbeat_interval_seconds,
            coordinator=self.coordinator,
        ) as hb:
            self._current_heartbeat = hb
            try:
                # 0. Media Acquisition
                if job.media is not None:
                    # Pre-existing media uploaded directly from Android
                    if job.stages.acquisition == StageStatus.PENDING:
                        if not self._update_stage(job, "acquisition", StageStatus.SKIPPED):
                            return False
                elif job.stages.acquisition != StageStatus.SUCCEEDED:
                    if job.original_url:
                        if not self._heartbeat(job):
                            logger.warning("Aborting job %s: lost lease before media acquisition", job.item_id)
                            return False
                        logger.info("Attempting media acquisition for job %s from %s", job.item_id, job.original_url)
                        dl_res = self.downloader.download(job.original_url, job_dir, job.item_id)
                        if self._is_cancelled(job):
                            logger.warning("Aborting job %s: worker lease lost during media acquisition", job.item_id)
                            return False

                        if dl_res.is_success and dl_res.media_path is not None:
                            if self._is_cancelled(job):
                                logger.warning("Aborting job %s: worker lease lost before media upload", job.item_id)
                                return False
                            media_hash = compute_file_sha256(dl_res.media_path)
                            logger.info("Uploading acquired media to persistent storage (sha256=%s)...", media_hash)
                            drive_file_id = self.storage.upload_media(
                                local_media_path=dl_res.media_path,
                                item_id=job.item_id,
                                mime_type=dl_res.mime_type,
                                original_file_name=dl_res.file_name,
                                content_hash=media_hash,
                                generation=job.generation,
                                attempt_epoch=active_epoch,
                            )
                            if not drive_file_id:
                                self._update_stage(job, "acquisition", StageStatus.FAILED, error="Failed to upload acquired media")
                                return self._handle_job_failure(job, "Failed to upload acquired media to persistent storage")

                            job.media = MediaInfo(
                                file_name=dl_res.file_name or dl_res.media_path.name,
                                mime_type=dl_res.mime_type,
                                drive_file_id=drive_file_id,
                                size_bytes=dl_res.size_bytes,
                                duration_ms=dl_res.duration_ms,
                                content_hash=media_hash,
                            )
                            if not job.caption and dl_res.caption:
                                job.caption = dl_res.caption
                            local_media_path = dl_res.media_path
                            media_artifact = ArtifactRef(
                                drive_file_id=drive_file_id,
                                mime_type=dl_res.mime_type,
                                size_bytes=dl_res.size_bytes or 0,
                                content_hash=media_hash,
                                name=job.media.file_name,
                                generation=job.generation,
                            )
                            if not self._update_stage(job, "acquisition", StageStatus.SUCCEEDED, artifact=media_artifact):
                                logger.warning("Aborting job %s: lost lease during acquisition update", job.item_id)
                                return False
                        elif dl_res.is_retryable and job.retry_count < job.max_retries:
                            logger.warning(
                                "Temporary media acquisition restriction for %s: %s; scheduling retry",
                                job.item_id, dl_res.error_message
                            )
                            self._update_stage(job, "acquisition", StageStatus.FAILED, error=dl_res.error_message)
                            return self._handle_job_failure(
                                job, f"Temporary media acquisition restriction: {dl_res.error_message}"
                            )
                        else:
                            logger.warning(
                                "Media acquisition unavailable or exhausted for %s: %s",
                                job.item_id, dl_res.error_message
                            )
                            if not self._update_stage(job, "acquisition", StageStatus.SKIPPED, error=dl_res.error_message):
                                return False
                            if not self._update_stage(job, "audio_extraction", StageStatus.SKIPPED):
                                return False
                            if not self._update_stage(job, "transcription", StageStatus.SKIPPED):
                                return False
                    else:
                        if not self._update_stage(job, "acquisition", StageStatus.SKIPPED):
                            return False
                        if not self._update_stage(job, "audio_extraction", StageStatus.SKIPPED):
                            return False
                        if not self._update_stage(job, "transcription", StageStatus.SKIPPED):
                            return False

                # 1. Media Retrieval & Audio Extraction
                if job.media is None:
                    if job.stages.audio_extraction == StageStatus.PENDING:
                        if not self._update_stage(job, "audio_extraction", StageStatus.SKIPPED):
                            return False
                    if job.stages.transcription == StageStatus.PENDING:
                        if not self._update_stage(job, "transcription", StageStatus.SKIPPED):
                            return False
                elif (
                    job.stages.audio_extraction != StageStatus.SUCCEEDED
                    and job.stages.audio_extraction != StageStatus.SKIPPED
                ):
                    if not self._heartbeat(job):
                        logger.warning("Aborting job %s: lost lease before audio extraction", job.item_id)
                        return False

                    if local_media_path is None or not local_media_path.exists():
                        ext = Path(job.media.file_name).suffix or ".mp4"
                        local_media_path = job_dir / f"input_media{ext}"

                        logger.info("Downloading media file %s from cloud storage...", job.media.drive_file_id)
                        expected_hash = job.media.content_hash if job.media else None
                        download_ok = self.storage.download_media(
                            job.media.drive_file_id,
                            local_media_path,
                            expected_sha256=expected_hash,
                        )
                        if not download_ok:
                            self._update_stage(job, "audio_extraction", StageStatus.FAILED, error="Failed to download media file or SHA-256 verification failed")
                            return self._handle_job_failure(job, "Failed to download media file from persistent storage")

                    # Extract audio
                    try:
                        extract_res = self.audio_extractor.extract(
                            local_media_path,
                            local_wav_path,
                            is_cancelled=self._is_cancelled,
                        )
                    except TypeError as te:
                        if "is_cancelled" in str(te):
                            extract_res = self.audio_extractor.extract(
                                local_media_path,
                                local_wav_path,
                            )
                        else:
                            raise
                    if self._is_cancelled(job):
                        logger.warning("Aborting job %s: worker lease lost during audio extraction", job.item_id)
                        return False
                    if extract_res.no_audio_track:
                        logger.info("Media has no audio track; skipping audio extraction and transcription")
                        if not self._update_stage(job, "audio_extraction", StageStatus.SKIPPED):
                            return False
                        if not self._update_stage(job, "transcription", StageStatus.SKIPPED):
                            return False
                    elif not extract_res.success:
                        self._update_stage(job, "audio_extraction", StageStatus.FAILED, error=extract_res.error)
                        return self._handle_job_failure(job, f"Audio extraction failed: {extract_res.error}")
                    else:
                        if self._is_cancelled(job):
                            logger.warning("Aborting job %s: worker lease lost before audio upload", job.item_id)
                            return False
                        audio_hash = compute_file_sha256(local_wav_path)
                        logger.info("Uploading extracted WAV to persistent cloud storage (sha256=%s)...", audio_hash)
                        audio_drive_id = self.storage.upload_audio(
                            local_wav_path,
                            job.item_id,
                            content_hash=audio_hash,
                            generation=job.generation,
                            attempt_epoch=active_epoch,
                        )
                        job.results.audio_drive_file_id = audio_drive_id
                        job.results.audio_size_bytes = extract_res.size_bytes
                        job.results.audio_content_hash = audio_hash
                        audio_artifact = ArtifactRef(
                            drive_file_id=audio_drive_id or "",
                            mime_type="audio/wav",
                            size_bytes=extract_res.size_bytes or 0,
                            content_hash=audio_hash,
                            name=(
                                f"{job.item_id}_gen{job.generation}_e{active_epoch}.wav"
                                if active_epoch > 1
                                else (f"{job.item_id}_gen{job.generation}.wav" if job.generation > 1 else f"{job.item_id}.wav")
                            ),
                            generation=job.generation,
                            attempt_epoch=active_epoch,
                        )
                        if not self._update_stage(job, "audio_extraction", StageStatus.SUCCEEDED, artifact=audio_artifact):
                            logger.warning("Aborting job %s: lost lease during audio extraction update", job.item_id)
                            return False

                # 1b. On-Screen Visual Text (OCR) Extraction
                if local_media_path and local_media_path.exists() and not job.results.ocr_text:
                    if not self._heartbeat(job):
                        logger.warning("Aborting job %s: lost lease before OCR extraction", job.item_id)
                        return False
                    try:
                        ocr_outcome = self.ocr_extractor.extract(
                            local_media_path,
                            job_dir,
                            is_cancelled=self._is_cancelled,
                        )
                    except TypeError as te:
                        if "is_cancelled" in str(te):
                            ocr_outcome = self.ocr_extractor.extract(
                                local_media_path,
                                job_dir,
                            )
                        else:
                            raise
                    if self._is_cancelled(job):
                        logger.warning("Aborting job %s: worker lease lost during OCR extraction", job.item_id)
                        return False
                    if ocr_outcome.text:
                        logger.info("Extracted %d chars of visual on-screen text via OCR", len(ocr_outcome.text))
                        job.results.ocr_text = ocr_outcome.text
                        if not self._update_stage(job, "ocr", StageStatus.SUCCEEDED):
                            logger.warning("Aborting job %s: lost lease during OCR update", job.item_id)
                            return False
                    else:
                        if not self._update_stage(job, "ocr", StageStatus.SKIPPED):
                            return False
                elif job.stages.ocr == StageStatus.PENDING:
                    if not self._update_stage(job, "ocr", StageStatus.SKIPPED):
                        return False

                # 2. Whisper Speech-to-Text Transcription
                if (
                    job.stages.audio_extraction == StageStatus.SUCCEEDED
                    and job.stages.transcription != StageStatus.SUCCEEDED
                    and job.stages.transcription != StageStatus.SKIPPED
                ):
                    if not self._heartbeat(job):
                        logger.warning("Aborting job %s: lost lease before Whisper transcription", job.item_id)
                        return False

                    # Ensure local WAV exists (either freshly extracted or downloaded)
                    if not local_wav_path.exists() and job.results.audio_drive_file_id:
                        self.storage.download_media(
                            job.results.audio_drive_file_id,
                            local_wav_path,
                            expected_sha256=job.results.audio_content_hash,
                        )

                    if local_wav_path.exists():
                        trans_res = self.transcriber.transcribe(local_wav_path)
                        if self._is_cancelled(job):
                            logger.warning("Aborting job %s: worker lease lost during Whisper transcription", job.item_id)
                            return False
                        if not trans_res.success:
                            self._update_stage(job, "transcription", StageStatus.FAILED, error=trans_res.error)
                            return self._handle_job_failure(job, f"Whisper transcription failed: {trans_res.error}")
                        job.results.transcript = trans_res.transcript
                        job.results.transcript_model = trans_res.model_name
                        if not self._update_stage(job, "transcription", StageStatus.SUCCEEDED):
                            logger.warning("Aborting job %s: lost lease during transcription update", job.item_id)
                            return False
                    else:
                        logger.warning("No audio file available for transcription on item %s", job.item_id)
                        if not self._update_stage(job, "transcription", StageStatus.SKIPPED):
                            return False

                # Check for URL-only job with no media and no text metadata
                usable_text = any(
                    bool(t and t.strip())
                    for t in [job.results.transcript, job.caption, job.user_notes, job.results.ocr_text]
                )
                if job.media is None and not usable_text:
                    logger.info(
                        "Job %s has no media, caption, notes, or OCR text; manual input required",
                        job.item_id,
                    )
                    self._update_stage(job, "audio_extraction", StageStatus.SKIPPED)
                    self._update_stage(job, "transcription", StageStatus.SKIPPED)
                    self._update_stage(job, "gemini_analysis", StageStatus.SKIPPED)
                    job.status = JobStatus.PARTIAL
                    job.error_message = (
                        "URL-only item without media or metadata; share reel video or provide notes"
                    )
                    job.updated_at = int(time.time() * 1000)
                    return self.storage.update_job(job)

                # 3. Gemini Content Intelligence & Structured Enrichment (Optional Layer)
                if job.stages.gemini_analysis != StageStatus.SUCCEEDED and job.stages.gemini_analysis != StageStatus.SKIPPED:
                    if not self._heartbeat(job):
                        logger.warning("Aborting job %s: lost lease before Gemini analysis", job.item_id)
                        return False

                    transcript_text = job.results.transcript
                    title = job.media.file_name if job.media else None
                    caption = job.caption
                    notes = job.user_notes

                    ai_res = self.gemini_analyzer.analyze(
                        title=title,
                        caption=caption,
                        transcript=transcript_text,
                        notes=notes,
                        ocr_text=job.results.ocr_text,
                        category=job.category,
                        user_tags=job.user_tags,
                    )
                    if self._is_cancelled(job):
                        logger.warning("Aborting job %s: worker lease lost during Gemini analysis", job.item_id)
                        return False
                    if not ai_res.success:
                        if "not configured" in (ai_res.error or "").lower():
                            logger.info("GEMINI_API_KEY is not configured; skipping optional AI enrichment for job %s", job.item_id)
                            self._update_stage(job, "gemini_analysis", StageStatus.SKIPPED)
                        else:
                            self._update_stage(job, "gemini_analysis", StageStatus.FAILED, error=ai_res.error)
                            return self._handle_job_failure(
                                job,
                                f"Gemini enrichment failed: {ai_res.error}",
                                is_retryable=ai_res.is_retryable,
                                retry_after_seconds=ai_res.retry_after_seconds,
                            )
                    else:
                        job.results.analysis = ai_res.result
                        if not self._update_stage(job, "gemini_analysis", StageStatus.SUCCEEDED):
                            logger.warning("Aborting job %s: lost lease during Gemini update", job.item_id)
                            return False

                # Verify honest completion criteria
                if self._is_cancelled(job):
                    logger.error("Job %s cannot be completed: worker lease lost", job.item_id)
                    return False

                has_valid_analysis = (
                    job.stages.gemini_analysis == StageStatus.SUCCEEDED
                    and job.results.analysis is not None
                    and bool(job.results.analysis.summary or job.results.analysis.key_takeaways)
                )
                ai_skipped_or_unconfigured = (
                    job.stages.gemini_analysis == StageStatus.SKIPPED
                    or not self.config.gemini_api_key
                )
                has_transcription_or_skipped = (
                    job.stages.transcription in (StageStatus.SUCCEEDED, StageStatus.SKIPPED)
                )
                has_acquisition_settled = (
                    job.stages.acquisition in (StageStatus.SUCCEEDED, StageStatus.SKIPPED)
                    or job.media is not None
                )
                has_audio_settled = (
                    job.stages.audio_extraction in (StageStatus.SUCCEEDED, StageStatus.SKIPPED)
                )

                # State 1: Fully processed (all applicable media stages + Gemini AI analysis succeeded)
                if has_valid_analysis and has_transcription_or_skipped and has_acquisition_settled and has_audio_settled:
                    job.status = JobStatus.COMPLETED
                    job.error_message = None
                    job.updated_at = int(time.time() * 1000)
                    self._record_completion_transition(job)
                    if self.coordinator:
                        self.coordinator.complete_job(job.item_id, self.config.runner_id, job)
                    if not self.storage.update_job(job):
                        logger.warning("Aborting job %s: lost lease during completion update", job.item_id)
                        return False
                    logger.info("Successfully completed all processing stages for job %s", job.item_id)
                    return True

                # State 2: Processed without AI analysis (All media & transcription completed; AI analysis was optional/skipped)
                elif ai_skipped_or_unconfigured and has_transcription_or_skipped and has_acquisition_settled and has_audio_settled:
                    job.status = JobStatus.COMPLETED
                    job.error_message = None
                    job.updated_at = int(time.time() * 1000)
                    self._record_completion_transition(job)
                    if self.coordinator:
                        self.coordinator.complete_job(job.item_id, self.config.runner_id, job)
                    if not self.storage.update_job(job):
                        logger.warning("Aborting job %s: lost lease during completion update", job.item_id)
                        return False
                    logger.info("Successfully completed media processing without AI analysis for job %s", job.item_id)
                    return True

                # State 3: Partial results (usable data saved, but one or more attempted stages incomplete or failed)
                elif job.results.transcript or has_valid_analysis or job.results.ocr_text or job.media or job.caption or job.user_notes:
                    job.status = JobStatus.PARTIAL
                    job.error_message = job.error_message or "Partial processing: one or more stages were incomplete or skipped"
                    job.updated_at = int(time.time() * 1000)
                    if self.coordinator:
                        self.coordinator.complete_job(job.item_id, self.config.runner_id, job)
                    if not self.storage.update_job(job):
                        logger.warning("Aborting job %s: lost lease during partial completion update", job.item_id)
                        return False
                    logger.warning("Job %s marked PARTIAL (some stages succeeded, others incomplete)", job.item_id)
                    return True

                # State 4: Permanent failure (no usable stage results)
                else:
                    job.status = JobStatus.FAILED
                    job.error_message = job.error_message or "No stage results produced"
                    job.updated_at = int(time.time() * 1000)
                    if self.coordinator:
                        self.coordinator.fail_job(
                            job.item_id,
                            self.config.runner_id,
                            job.error_message,
                            is_retryable=False,
                        )
                    if not self.storage.update_job(job):
                        logger.warning("Failed to persist FAILED status for job %s: lost lease or storage error", job.item_id)
                    logger.error("Job %s marked FAILED (no valid stage results)", job.item_id)
                    return False

            except Exception as e:
                logger.error("Unhandled exception during job %s processing: %s", job.item_id, e, exc_info=True)
                return self._handle_job_failure(job, f"Unhandled worker exception: {e}")

            finally:
                self._current_heartbeat = None
                # Clean up local temporary media and WAV files
                if job_dir.exists():
                    shutil.rmtree(job_dir, ignore_errors=True)

    def _record_failure_transition(self, job: JobManifest, error: str) -> None:
        """Appends a terminal FAILED transition record to immutable storage and updates projection fields."""
        seq = self._next_seq(job.item_id)
        fail_trans = TransitionRecord(
            item_id=job.item_id,
            generation=job.generation,
            attempt_epoch=job.active_attempt_epoch or 1,
            transition_seq=seq,
            transition_type=TransitionType.FAILED,
            attempt_id=self.config.runner_id,
            previous_transition_id=self._last_transition_id.get(job.item_id),
            previous_hash=self._last_transition_hash.get(job.item_id),
            payload={"error_message": error, "retry_count": job.retry_count},
            created_at=job.updated_at,
        )
        self.storage.append_transition(fail_trans)
        self._last_transition_id[job.item_id] = fail_trans.deterministic_transition_id
        self._last_transition_hash[job.item_id] = fail_trans.compute_transition_hash()
        job.projection_version = (job.projection_version or 1) + 1
        job.last_transition_id = fail_trans.deterministic_transition_id
        job.last_transition_hash = fail_trans.compute_transition_hash()

    def _handle_job_failure(
        self,
        job: JobManifest,
        error: str,
        is_retryable: bool = True,
        retry_after_seconds: Optional[float] = None,
    ) -> bool:
        logger.error("Job %s failed: %s (is_retryable=%s)", job.item_id, error, is_retryable)
        now_ms = int(time.time() * 1000)
        job.error_message = error
        job.updated_at = now_ms

        if not is_retryable:
            # Permanent error: abort immediately without consuming retries
            job.status = JobStatus.FAILED
            job.next_retry_at = None
            self._record_failure_transition(job, error)
            logger.error("Job %s failed with permanent error; marked FAILED immediately", job.item_id)
            if self.coordinator:
                self.coordinator.fail_job(
                    job_id=job.item_id,
                    worker_id=self.config.runner_id,
                    error=error,
                    is_retryable=False,
                )
            if not self.storage.update_job(job):
                logger.warning("Failed to persist permanent failure for job %s: lost lease or storage error", job.item_id)
            return False

        job.retry_count += 1
        if job.retry_count >= job.max_retries:
            job.next_retry_at = None
            # Honest partial vs failed classification
            if job.results.transcript or job.stages.transcription == StageStatus.SUCCEEDED:
                job.status = JobStatus.PARTIAL
                logger.warning(
                    "Job %s exceeded retries but preserved partial transcript results; marked PARTIAL",
                    job.item_id
                )
            else:
                job.status = JobStatus.FAILED
                self._record_failure_transition(job, error)
                logger.error("Job %s exceeded maximum retries (%d); marked FAILED", job.item_id, job.max_retries)
        else:
            job.status = JobStatus.WAITING_RETRY
            if retry_after_seconds is not None and retry_after_seconds > 0:
                delay_ms = int(retry_after_seconds * 1000)
            else:
                base_delay_ms = 60_000
                backoff_ms = base_delay_ms * (2 ** (job.retry_count - 1))
                jitter = 0.8 + 0.4 * random.random()
                delay_ms = int(backoff_ms * jitter)
            job.next_retry_at = now_ms + delay_ms
            logger.info(
                "Job %s scheduled for retry (attempt %d/%d, next_retry_at in %d seconds)",
                job.item_id, job.retry_count, job.max_retries, delay_ms // 1000
            )

        if self.coordinator:
            self.coordinator.fail_job(
                job_id=job.item_id,
                worker_id=self.config.runner_id,
                error=error,
                is_retryable=is_retryable,
                next_retry_at=job.next_retry_at,
                max_retries=job.max_retries,
            )
        if not self.storage.update_job(job):
            logger.warning("Failed to persist retry state (%s) for job %s: lost lease or storage error", job.status.value, job.item_id)
        return False

