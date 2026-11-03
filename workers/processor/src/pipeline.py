"""Pipeline coordinator for executing multi-stage cloud processing jobs."""

from __future__ import annotations

import logging
import shutil
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from .config import WorkerConfig
from .models import JobManifest, JobStatus, MediaInfo, StageStatus
from .stages.audio_extractor import FFmpegAudioExtractor
from .stages.downloader import DownloadStatus, MediaDownloader, YtDlpDownloader
from .stages.gemini_analyzer import GeminiAnalyzer
from .stages.transcriber import WhisperTranscriber
from .storage import StorageClient

logger = logging.getLogger(__name__)


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
        audio_extractor: Optional[FFmpegAudioExtractor] = None,
        transcriber: Optional[WhisperTranscriber] = None,
        gemini_analyzer: Optional[GeminiAnalyzer] = None,
        downloader: Optional[MediaDownloader] = None,
    ):
        self.config = config
        self.storage = storage
        self.audio_extractor = audio_extractor or FFmpegAudioExtractor()
        self.transcriber = transcriber or WhisperTranscriber(model_name=config.whisper_model)
        self.gemini_analyzer = gemini_analyzer or GeminiAnalyzer(
            api_key=config.gemini_api_key,
            model=config.gemini_model,
        )
        self.downloader = downloader or YtDlpDownloader()

    def run_batch(self) -> BatchRunSummary:
        """Finds and executes up to max_jobs_per_run pending jobs."""
        logger.info("Listing queued jobs (limit=%d)...", self.config.max_jobs_per_run)
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

        now_ms = int(time.time() * 1000)
        job.status = JobStatus.PROCESSING
        job.claimed_by = self.config.runner_id
        job.claimed_at = now_ms
        job.updated_at = now_ms
        job.lease_timeout_ms = self.config.lease_timeout_ms

        if not self.storage.claim_job(job, runner_id=self.config.runner_id):
            logger.warning(
                "Could not claim job %s (concurrent worker claimed it or lease still active); skipping",
                job.item_id,
            )
            return False

        job_dir = self.config.work_dir / job.item_id
        job_dir.mkdir(parents=True, exist_ok=True)

        local_media_path: Optional[Path] = None
        local_wav_path = job_dir / f"{job.item_id}_audio.wav"

        try:
            # 0. Media Acquisition
            if job.media is not None:
                # Pre-existing media uploaded directly from Android
                if job.stages.acquisition == StageStatus.PENDING:
                    job.stages.acquisition = StageStatus.SKIPPED
            elif job.stages.acquisition != StageStatus.SUCCEEDED:
                if job.original_url:
                    logger.info("Attempting media acquisition for job %s from %s", job.item_id, job.original_url)
                    dl_res = self.downloader.download(job.original_url, job_dir, job.item_id)
                    if dl_res.is_success and dl_res.media_path is not None:
                        logger.info("Uploading acquired media to persistent storage...")
                        drive_file_id = self.storage.upload_media(
                            local_media_path=dl_res.media_path,
                            item_id=job.item_id,
                            mime_type=dl_res.mime_type,
                            original_file_name=dl_res.file_name,
                        )
                        if not drive_file_id:
                            return self._handle_job_failure(job, "Failed to upload acquired media to persistent storage")

                        job.media = MediaInfo(
                            file_name=dl_res.file_name or dl_res.media_path.name,
                            mime_type=dl_res.mime_type,
                            drive_file_id=drive_file_id,
                            size_bytes=dl_res.size_bytes,
                            duration_ms=dl_res.duration_ms,
                        )
                        if not job.caption and dl_res.caption:
                            job.caption = dl_res.caption
                        local_media_path = dl_res.media_path
                        job.stages.acquisition = StageStatus.SUCCEEDED
                        job.updated_at = int(time.time() * 1000)
                        self.storage.update_job(job)
                    elif dl_res.is_retryable and job.retry_count < job.max_retries:
                        logger.warning(
                            "Temporary media acquisition restriction for %s: %s; scheduling retry",
                            job.item_id, dl_res.error_message
                        )
                        return self._handle_job_failure(
                            job, f"Temporary media acquisition restriction: {dl_res.error_message}"
                        )
                    else:
                        logger.warning(
                            "Media acquisition unavailable or exhausted for %s: %s",
                            job.item_id, dl_res.error_message
                        )
                        job.stages.acquisition = StageStatus.SKIPPED
                        job.stages.audio_extraction = StageStatus.SKIPPED
                        job.stages.transcription = StageStatus.SKIPPED
                else:
                    job.stages.acquisition = StageStatus.SKIPPED
                    job.stages.audio_extraction = StageStatus.SKIPPED
                    job.stages.transcription = StageStatus.SKIPPED

            # 1. Media Retrieval & Audio Extraction
            if job.media is None:
                if job.stages.audio_extraction == StageStatus.PENDING:
                    job.stages.audio_extraction = StageStatus.SKIPPED
                if job.stages.transcription == StageStatus.PENDING:
                    job.stages.transcription = StageStatus.SKIPPED
            elif (
                job.stages.audio_extraction != StageStatus.SUCCEEDED
                and job.stages.audio_extraction != StageStatus.SKIPPED
            ):
                if local_media_path is None or not local_media_path.exists():
                    ext = Path(job.media.file_name).suffix or ".mp4"
                    local_media_path = job_dir / f"input_media{ext}"

                    logger.info("Downloading media file %s from cloud storage...", job.media.drive_file_id)
                    download_ok = self.storage.download_media(job.media.drive_file_id, local_media_path)
                    if not download_ok:
                        return self._handle_job_failure(job, "Failed to download media file from persistent storage")

                # Extract audio
                extract_res = self.audio_extractor.extract(local_media_path, local_wav_path)
                if extract_res.no_audio_track:
                    logger.info("Media has no audio track; skipping audio extraction and transcription")
                    job.stages.audio_extraction = StageStatus.SKIPPED
                    job.stages.transcription = StageStatus.SKIPPED
                elif not extract_res.success:
                    return self._handle_job_failure(job, f"Audio extraction failed: {extract_res.error}")
                else:
                    logger.info("Uploading extracted WAV to persistent cloud storage...")
                    audio_drive_id = self.storage.upload_audio(local_wav_path, job.item_id)
                    job.results.audio_drive_file_id = audio_drive_id
                    job.results.audio_size_bytes = extract_res.size_bytes
                    job.stages.audio_extraction = StageStatus.SUCCEEDED
                    job.updated_at = int(time.time() * 1000)
                    self.storage.update_job(job)

            # 2. Whisper Speech-to-Text Transcription
            if (
                job.stages.audio_extraction == StageStatus.SUCCEEDED
                and job.stages.transcription != StageStatus.SUCCEEDED
                and job.stages.transcription != StageStatus.SKIPPED
            ):
                # Ensure local WAV exists (either freshly extracted or downloaded)
                if not local_wav_path.exists() and job.results.audio_drive_file_id:
                    self.storage.download_media(job.results.audio_drive_file_id, local_wav_path)

                if local_wav_path.exists():
                    trans_res = self.transcriber.transcribe(local_wav_path)
                    if not trans_res.success:
                        return self._handle_job_failure(job, f"Whisper transcription failed: {trans_res.error}")
                    job.results.transcript = trans_res.transcript
                    job.results.transcript_model = trans_res.model_name
                    job.stages.transcription = StageStatus.SUCCEEDED
                    job.updated_at = int(time.time() * 1000)
                    self.storage.update_job(job)
                else:
                    logger.warning("No audio file available for transcription on item %s", job.item_id)
                    job.stages.transcription = StageStatus.SKIPPED

            # Check for URL-only job with no media and no text metadata
            usable_text = any(
                bool(t and t.strip())
                for t in [job.results.transcript, job.caption, job.user_notes]
            )
            if job.media is None and not usable_text:
                logger.info(
                    "Job %s has no media, caption, or notes; manual input required",
                    job.item_id,
                )
                job.stages.audio_extraction = StageStatus.SKIPPED
                job.stages.transcription = StageStatus.SKIPPED
                job.stages.gemini_analysis = StageStatus.SKIPPED
                job.status = JobStatus.PARTIAL
                job.error_message = (
                    "URL-only item without media or metadata; share reel video or provide notes"
                )
                job.updated_at = int(time.time() * 1000)
                self.storage.update_job(job)
                return True

            # 3. Gemini Content Intelligence & Structured Enrichment
            if job.stages.gemini_analysis != StageStatus.SUCCEEDED:
                transcript_text = job.results.transcript
                title = job.media.file_name if job.media else None
                caption = job.caption
                notes = job.user_notes

                ai_res = self.gemini_analyzer.analyze(
                    title=title,
                    caption=caption,
                    transcript=transcript_text,
                    notes=notes,
                )
                if not ai_res.success:
                    return self._handle_job_failure(job, f"Gemini enrichment failed: {ai_res.error}")

                job.results.analysis = ai_res.result
                job.stages.gemini_analysis = StageStatus.SUCCEEDED
                job.updated_at = int(time.time() * 1000)
                self.storage.update_job(job)

            # Verify honest completion criteria
            has_valid_analysis = (
                job.stages.gemini_analysis == StageStatus.SUCCEEDED
                and job.results.analysis is not None
                and bool(job.results.analysis.summary or job.results.analysis.key_takeaways)
            )
            has_transcription_or_skipped = (
                job.stages.transcription in (StageStatus.SUCCEEDED, StageStatus.SKIPPED)
            )

            if has_valid_analysis and has_transcription_or_skipped:
                job.status = JobStatus.COMPLETED
                job.error_message = None
                job.updated_at = int(time.time() * 1000)
                self.storage.update_job(job)
                logger.info("Successfully completed all processing stages for job %s", job.item_id)
                return True
            elif job.results.transcript or has_valid_analysis:
                job.status = JobStatus.PARTIAL
                job.error_message = "Partial processing: one or more stages were incomplete or skipped"
                job.updated_at = int(time.time() * 1000)
                self.storage.update_job(job)
                logger.warning("Job %s marked PARTIAL (some stages succeeded, others incomplete)", job.item_id)
                return True
            else:
                job.status = JobStatus.FAILED
                job.error_message = "No stage results produced"
                job.updated_at = int(time.time() * 1000)
                self.storage.update_job(job)
                logger.error("Job %s marked FAILED (no valid stage results)", job.item_id)
                return False

        except Exception as e:
            logger.error("Unhandled exception during job %s processing: %s", job.item_id, e, exc_info=True)
            return self._handle_job_failure(job, f"Unhandled worker exception: {e}")

        finally:
            # Clean up local temporary media and WAV files
            if job_dir.exists():
                shutil.rmtree(job_dir, ignore_errors=True)

    def _handle_job_failure(self, job: JobManifest, error: str) -> bool:
        logger.error("Job %s failed: %s", job.item_id, error)
        now_ms = int(time.time() * 1000)
        job.retry_count += 1
        job.error_message = error
        job.updated_at = now_ms

        if job.retry_count >= job.max_retries:
            # Honest partial vs failed classification
            if job.results.transcript or job.stages.transcription == StageStatus.SUCCEEDED:
                job.status = JobStatus.PARTIAL
                logger.warning(
                    "Job %s exceeded retries but preserved partial transcript results; marked PARTIAL",
                    job.item_id
                )
            else:
                job.status = JobStatus.FAILED
                logger.error("Job %s exceeded maximum retries (%d); marked FAILED", job.item_id, job.max_retries)
        else:
            job.status = JobStatus.WAITING_RETRY
            logger.info("Job %s scheduled for retry (attempt %d/%d)", job.item_id, job.retry_count, job.max_retries)

        self.storage.update_job(job)
        return False

