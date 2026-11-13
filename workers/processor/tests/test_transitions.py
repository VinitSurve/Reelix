"""Deterministic fault-injection and concurrency unit tests for append-only transition log and projection consistency.

Verifies:
- Runner termination immediately after Drive transition creation.
- Recovery from a new GitHub Actions runner.
- Delayed Drive listing visibility.
- Duplicate logical transition recovery.
- Two workers attempting to claim the same next epoch.
- Stale epoch transition arriving after a newer epoch.
- Same-predecessor transition forks.
- Missing transition sequence.
- Projection rollback followed by successful reconstruction.
- Runner termination between transition creation and projection publication.
- Long-running heartbeat volume and recovery.
"""

from __future__ import annotations

import shutil
import tempfile
import time
import unittest
from pathlib import Path

from workers.processor.src.config import WorkerConfig
from workers.processor.src.models import (
    ArtifactRef,
    GeminiAnalysisResult,
    JobManifest,
    JobStatus,
    MediaInfo,
    StageStates,
    StageStatus,
    TransitionRecord,
    TransitionType,
    fold_transitions,
)
from workers.processor.src.pipeline import CloudProcessingPipeline
from workers.processor.src.stages.audio_extractor import AudioExtractionOutcome
from workers.processor.src.stages.downloader import DownloadResult, DownloadStatus, MediaDownloader
from workers.processor.src.stages.gemini_analyzer import GeminiAnalysisOutcome
from workers.processor.src.stages.ocr_extractor import OcrExtractionOutcome
from workers.processor.src.stages.transcriber import TranscriptionOutcome
from workers.processor.src.storage.mock_storage import MockStorageClient


class FakeDownloader(MediaDownloader):
    def __init__(self, success: bool = True):
        self.success = success
        self.called = 0

    def download(self, url: str, output_dir: Path, item_id: str) -> DownloadResult:
        self.called += 1
        if not self.success:
            return DownloadResult(status=DownloadStatus.FAILED, error_message="Network error")
        out_file = output_dir / f"media_{item_id}.mp4"
        out_file.write_bytes(b"dummy video data for transition test")
        return DownloadResult(
            status=DownloadStatus.SUCCESS,
            media_path=out_file,
            file_name=out_file.name,
            mime_type="video/mp4",
            size_bytes=len(b"dummy video data for transition test"),
        )


class FakeExtractor:
    def __init__(self, success: bool = True):
        self.success = success
        self.called = 0

    def extract(self, input_video: Path, output_wav: Path, timeout_seconds: int = 180, **kwargs):
        self.called += 1
        if not self.success:
            return AudioExtractionOutcome(success=False, error="Extraction failed")
        output_wav.write_bytes(b"dummy wav data for transition test")
        return AudioExtractionOutcome(success=True, wav_path=output_wav, size_bytes=32)


class FakeTranscriber:
    def __init__(self, success: bool = True):
        self.success = success
        self.called = 0

    def transcribe(self, wav_path: Path):
        self.called += 1
        if not self.success:
            return TranscriptionOutcome(success=False, error="Transcription failed")
        return TranscriptionOutcome(success=True, transcript="Durable transition transcript", model_name="base")


class FakeOcrExtractor:
    def __init__(self, text: str = "Visual on-screen text"):
        self.text = text
        self.called = 0

    def extract(self, media_path: Path, output_dir: Path, **kwargs):
        self.called += 1
        return OcrExtractionOutcome(success=True, text=self.text)


class FakeAnalyzer:
    def __init__(self, success: bool = True):
        self.success = success
        self.called = 0

    def analyze(self, **kwargs):
        self.called += 1
        if not self.success:
            return GeminiAnalysisOutcome(success=False, error="Quota exceeded", is_retryable=True)
        return GeminiAnalysisOutcome(
            success=True,
            result=GeminiAnalysisResult(
                summary="Transition AI Summary",
                detailed_summary="Detailed AI analysis for transition log test",
                main_topic="Transitions",
                category="Engineering",
                keywords=["append-only", "projection"],
                tags=["transition", "durable"],
                key_takeaways=["Append only", "Fold projection"],
                important_concepts=["Fencing", "Idempotency"],
                actionable_steps=["Persist records", "Rebuild cache"],
                model_name="gemini-3.6-flash",
                generated_at=int(time.time()),
            ),
        )


class TestTransitionLogAndProjections(unittest.TestCase):
    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="reelix_trans_test_"))
        self.storage_dir = self.test_dir / "storage"
        self.work_dir = self.test_dir / "work"
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        self.work_dir.mkdir(parents=True, exist_ok=True)

        self.storage = MockStorageClient(root_dir=self.storage_dir)
        self.config = WorkerConfig(
            work_dir=self.work_dir,
            runner_id="gha-runner-1",
            mock_mode=True,
            lease_timeout_ms=60_000,
        )

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def _create_job(self, item_id: str) -> JobManifest:
        job = JobManifest(
            item_id=item_id,
            source="URL",
            original_url="https://www.instagram.com/reel/TransTest/",
            caption="Transition test reel",
            category="Engineering",
            user_tags=["test", "transitions"],
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
        )
        self.storage.update_job(job)
        return job

    def test_runner_termination_immediately_after_drive_transition_creation(self):
        """1. Runner 1 creates acquisition and audio extraction transitions, then crashes.
        Runner 2 resumes using folded transition state without repeating acquisition or extraction.
        """
        item_id = "item-term-test"
        job = self._create_job(item_id)

        dl = FakeDownloader()
        ext = FakeExtractor()
        trans = FakeTranscriber()
        ocr = FakeOcrExtractor()
        ai = FakeAnalyzer()

        p1 = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            downloader=dl,
            audio_extractor=ext,
            transcriber=trans,
            ocr_extractor=ocr,
            gemini_analyzer=ai,
        )

        # Runner 1 claims and executes partially
        p1.storage.claim_job(job, runner_id="gha-runner-1")
        # Emit CLAIM and ACQUIRE transitions
        c_trans = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="gha-runner-1",
            created_at=1000,
        )
        self.storage.append_transition(c_trans)

        media_meta = {
            "file_name": "media_item-term-test.mp4",
            "mime_type": "video/mp4",
            "drive_file_id": "file_media_term",
            "size_bytes": 1024,
        }
        acq_trans = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=2,
            transition_type=TransitionType.ACQUIRE,
            attempt_id="gha-runner-1",
            previous_transition_id=c_trans.deterministic_transition_id,
            previous_hash=c_trans.compute_transition_hash(),
            payload={"status": "SUCCEEDED", "media": media_meta},
            created_at=1010,
        )
        self.storage.append_transition(acq_trans)

        # Runner 1 terminates abruptly here! Lease expires.
        job.claimed_at = 0
        job.claimed_by = None
        self.storage.update_job(job)

        # Runner 2 starts on a fresh VM
        config_2 = WorkerConfig(work_dir=self.test_dir / "work_2", runner_id="gha-runner-2", mock_mode=True)
        config_2.work_dir.mkdir(parents=True, exist_ok=True)
        dl_2 = FakeDownloader()
        p2 = CloudProcessingPipeline(
            config=config_2,
            storage=self.storage,
            downloader=dl_2,
            audio_extractor=FakeExtractor(),
            transcriber=trans,
            ocr_extractor=ocr,
            gemini_analyzer=ai,
        )

        # Runner 2 should fold state and resume: acquisition was already done, so downloader is not called again!
        # Register mock media file so downstream stages find it
        dummy_media = self.storage.media_dir / "file_media_term"
        dummy_media.write_bytes(b"dummy media")
        self.storage.register_media("file_media_term", dummy_media)

        success = p2.process_job(job)
        self.assertTrue(success)
        self.assertEqual(0, dl_2.called, "Downloader should NOT be called again because acquisition was completed in transition log")

    def test_delayed_drive_listing_visibility(self):
        """2. Google Drive listing lag hides newly uploaded transition temporarily.
        find_transition() bounded retry discovers it once visible, preventing duplicate uploads.
        """
        item_id = "item-listing-lag"
        self._create_job(item_id)

        # Append transition
        trans = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="gha-runner-1",
            created_at=1000,
        )
        self.storage.append_transition(trans)

        # Simulate Drive eventual consistency lag: hide for 2 list attempts
        target_filename = f"{trans.deterministic_transition_id}.json"
        self.storage.simulated_listing_delay[target_filename] = 2

        # 1st list attempt: hidden
        t_list_1 = self.storage.list_transitions(item_id)
        self.assertEqual(0, len(t_list_1))

        # 2nd list attempt: hidden
        t_list_2 = self.storage.list_transitions(item_id)
        self.assertEqual(0, len(t_list_2))

        # 3rd list attempt: lag expired -> visible
        t_list_3 = self.storage.list_transitions(item_id)
        self.assertEqual(1, len(t_list_3))
        self.assertEqual(trans.deterministic_transition_id, t_list_3[0].deterministic_transition_id)

    def test_duplicate_logical_transition_recovery(self):
        """3. Network retries created two identical records for the same transition sequence.
        fold_transitions() recognizes identical payload hashes and deduplicates to single logical transition.
        """
        item_id = "item-dedup-test"
        payload = {"status": "SUCCEEDED", "dummy_val": 42}

        t1 = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="gha-1",
            payload={"status": "SUCCEEDED"},
            created_at=1000,
        )
        t2_dup_a = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=2,
            transition_type=TransitionType.ACQUIRE,
            attempt_id="gha-1",
            previous_transition_id=t1.deterministic_transition_id,
            previous_hash=t1.compute_transition_hash(),
            payload=payload,
            created_at=1010,
        )
        t2_dup_b = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=2,
            transition_type=TransitionType.ACQUIRE,
            attempt_id="gha-1",
            previous_transition_id=t1.deterministic_transition_id,
            previous_hash=t1.compute_transition_hash(),
            payload=payload,
            created_at=1011,
        )

        transitions = [t1, t2_dup_a, t2_dup_b]
        folded, warnings = fold_transitions(item_id, transitions)

        self.assertEqual(0, len(warnings))
        self.assertEqual(StageStatus.SUCCEEDED, folded.stages.acquisition)
        self.assertEqual(t2_dup_a.deterministic_transition_id, folded.last_transition_id)

    def test_two_workers_attempting_to_claim_same_next_epoch(self):
        """4. Worker B claims next epoch. Worker C attempts concurrent claim while lease active; rejected."""
        item_id = "item-concurrent-epoch"
        job = self._create_job(item_id)

        config_b = WorkerConfig(work_dir=self.test_dir / "b", runner_id="worker-b", mock_mode=True, lease_timeout_ms=60_000)
        config_c = WorkerConfig(work_dir=self.test_dir / "c", runner_id="worker-c", mock_mode=True, lease_timeout_ms=60_000)
        config_b.work_dir.mkdir(parents=True, exist_ok=True)
        config_c.work_dir.mkdir(parents=True, exist_ok=True)

        p_b = CloudProcessingPipeline(config=config_b, storage=self.storage)
        p_c = CloudProcessingPipeline(config=config_c, storage=self.storage)

        # Worker B claims
        claimed_b = self.storage.claim_job(job, runner_id="worker-b")
        self.assertTrue(claimed_b)

        # Worker C attempts claim while Worker B lease is active
        claimed_c = self.storage.claim_job(job, runner_id="worker-c")
        self.assertFalse(claimed_c, "Worker C must be rejected while Worker B lease is active")

    def test_stale_epoch_transition_arriving_after_newer_epoch(self):
        """5. Worker B has progressed in Epoch 2. A delayed Epoch 1 transition arrives late.
        fold_transitions() strictly ignores stale Epoch 1 transition and preserves Epoch 2.
        """
        item_id = "item-stale-epoch"

        # Epoch 1 records
        t_e1_s1 = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=1, transition_seq=1,
            transition_type=TransitionType.CLAIM, attempt_id="worker-a", created_at=1000,
        )

        # Epoch 2 records
        t_e2_s1 = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=2, transition_seq=1,
            transition_type=TransitionType.CLAIM, attempt_id="worker-b", created_at=2000,
        )
        t_e2_s2 = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=2, transition_seq=2,
            transition_type=TransitionType.ACQUIRE, attempt_id="worker-b",
            previous_transition_id=t_e2_s1.deterministic_transition_id,
            previous_hash=t_e2_s1.compute_transition_hash(),
            payload={"status": "SUCCEEDED", "media": {"file_name": "epoch2.mp4", "mime_type": "video/mp4", "drive_file_id": "f_e2", "size_bytes": 100}},
            created_at=2010,
        )

        # Delayed write from Epoch 1 lands late
        t_e1_s2_delayed = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=1, transition_seq=2,
            transition_type=TransitionType.ACQUIRE, attempt_id="worker-a",
            previous_transition_id=t_e1_s1.deterministic_transition_id,
            previous_hash=t_e1_s1.compute_transition_hash(),
            payload={"status": "SUCCEEDED", "media": {"file_name": "epoch1_stale.mp4", "mime_type": "video/mp4", "drive_file_id": "f_e1", "size_bytes": 50}},
            created_at=1010,
        )

        all_transitions = [t_e1_s1, t_e2_s1, t_e2_s2, t_e1_s2_delayed]
        folded, warnings = fold_transitions(item_id, all_transitions)

        # Authoritative state must be from Epoch 2
        self.assertEqual(2, folded.active_attempt_epoch)
        self.assertEqual("worker-b", folded.claimed_by)
        self.assertEqual("epoch2.mp4", folded.media.file_name)

    def test_same_predecessor_transition_forks(self):
        """6. Two transitions share the same predecessor and sequence, but divergent payload hashes.
        fold_transitions() flags integrity fork and marks status NEEDS_ATTENTION.
        """
        item_id = "item-fork-test"

        t1 = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=1, transition_seq=1,
            transition_type=TransitionType.CLAIM, attempt_id="worker-a", created_at=1000,
        )

        # Conflicting children
        t2_fork_a = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=1, transition_seq=2,
            transition_type=TransitionType.ACQUIRE, attempt_id="worker-a",
            previous_transition_id=t1.deterministic_transition_id,
            previous_hash=t1.compute_transition_hash(),
            payload={"hash": "branch_A_content"},
            created_at=1010,
        )
        t2_fork_b = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=1, transition_seq=2,
            transition_type=TransitionType.ACQUIRE, attempt_id="worker-b",
            previous_transition_id=t1.deterministic_transition_id,
            previous_hash=t1.compute_transition_hash(),
            payload={"hash": "branch_B_content"},
            created_at=1011,
        )

        folded, warnings = fold_transitions(item_id, [t1, t2_fork_a, t2_fork_b])

        self.assertTrue(any("Integrity fork" in w for w in warnings))
        self.assertEqual(JobStatus.NEEDS_ATTENTION, folded.status)

    def test_missing_transition_sequence(self):
        """7. Missing transition sequence (e.g. s01 and s03 exist, missing s02).
        fold_transitions() halts accumulation at s01 and warns of sequence gap.
        """
        item_id = "item-gap-test"

        t1 = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=1, transition_seq=1,
            transition_type=TransitionType.CLAIM, attempt_id="worker-a", created_at=1000,
        )
        t3_gap = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=1, transition_seq=3,
            transition_type=TransitionType.AUDIO_EXTRACTED, attempt_id="worker-a",
            previous_transition_id="g1_e1_s02_ACQUIRE",
            previous_hash="some_missing_hash",
            payload={"status": "SUCCEEDED"},
            created_at=1030,
        )

        folded, warnings = fold_transitions(item_id, [t1, t3_gap])

        self.assertTrue(any("Sequence gap" in w for w in warnings))
        self.assertEqual(StageStatus.PENDING, folded.stages.audio_extraction)
        self.assertEqual(t1.deterministic_transition_id, folded.last_transition_id)

    def test_projection_rollback_followed_by_successful_reconstruction(self):
        """8. Manifest projection is rolled back by stale write.
        rebuild_projection() reconstructs authoritative projection from transitions.
        """
        item_id = "item-reconstruct-test"
        job = self._create_job(item_id)

        # Run pipeline to completion
        p = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            downloader=FakeDownloader(),
            audio_extractor=FakeExtractor(),
            transcriber=FakeTranscriber(),
            ocr_extractor=FakeOcrExtractor(),
            gemini_analyzer=FakeAnalyzer(),
        )
        success = p.process_job(job)
        self.assertTrue(success)

        completed_manifest = self.storage.get_job(item_id)
        self.assertEqual(JobStatus.COMPLETED, completed_manifest.status)
        high_version = completed_manifest.projection_version

        # Simulate delayed stale raw projection write bypassing application checks
        stale_manifest = JobManifest.from_dict(completed_manifest.to_dict())
        stale_manifest.status = JobStatus.PROCESSING
        stale_manifest.projection_version = 2
        (self.storage.jobs_dir / f"{item_id}.json").write_text(stale_manifest.to_json(), encoding="utf-8")

        corrupt = self.storage.get_job(item_id)
        self.assertEqual(JobStatus.PROCESSING, corrupt.status)

        # Worker calls rebuild_projection()
        rebuilt = self.storage.rebuild_projection(item_id)
        self.assertIsNotNone(rebuilt)
        self.assertEqual(JobStatus.COMPLETED, rebuilt.status)
        self.assertGreaterEqual(rebuilt.projection_version, high_version)

    def test_runner_termination_between_transition_creation_and_projection_publication(self):
        """9. Transition is created on storage, but runner dies before projection is updated.
        Next runner folds transitions, discovers the committed stage, and advances state cleanly.
        """
        item_id = "item-mid-flight-death"
        job = self._create_job(item_id)

        # Claim
        c = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=1, transition_seq=1,
            transition_type=TransitionType.CLAIM, attempt_id="worker-a", created_at=1000,
        )
        self.storage.append_transition(c)

        # Commit Audio Extraction transition
        art = {
            "drive_file_id": "file_audio_durable",
            "size_bytes": 2048,
            "content_hash": "sha_audio_123",
            "generation": 1,
        }
        audio_trans = TransitionRecord(
            item_id=item_id, generation=1, attempt_epoch=1, transition_seq=2,
            transition_type=TransitionType.AUDIO_EXTRACTED, attempt_id="worker-a",
            previous_transition_id=c.deterministic_transition_id,
            previous_hash=c.compute_transition_hash(),
            payload={"status": "SUCCEEDED", "artifact": art},
            created_at=1010,
        )
        self.storage.append_transition(audio_trans)

        # Runner died before updating jobs/{item_id}.json (it still says QUEUED)
        proj_on_disk = self.storage.get_job(item_id)
        self.assertEqual(JobStatus.QUEUED, proj_on_disk.status)

        # Next runner starts and folds transitions
        rebuilt = self.storage.rebuild_projection(item_id)
        self.assertEqual(StageStatus.SUCCEEDED, rebuilt.stages.audio_extraction)
        self.assertEqual("file_audio_durable", rebuilt.artifacts["audio_extraction"].drive_file_id)

    def test_long_running_heartbeat_volume_and_recovery(self):
        """10. 20 heartbeat renewals over 10 minutes update projection lease without flooding transition log."""
        item_id = "item-heartbeat-volume"
        job = self._create_job(item_id)

        p = CloudProcessingPipeline(config=self.config, storage=self.storage)
        self.storage.claim_job(job, runner_id="gha-hb-worker")

        # Simulate 20 heartbeat renewals
        for _ in range(20):
            p._heartbeat(job)

        # Verify transitions directory contains zero or only valid stage transitions, NOT 20 heartbeat files
        transitions = self.storage.list_transitions(item_id)
        self.assertLessEqual(len(transitions), 2, "Heartbeats must NOT flood transitions directory with immutable files")

        # Verify lease was kept alive in projection
        current_job = self.storage.get_job(item_id)
        self.assertEqual(JobStatus.PROCESSING, current_job.status)
        self.assertGreater(current_job.claimed_at, 1000)

    def test_fresh_runner_crash_after_drive_creation_before_response_receipt(self):
        """Blocker 3: Worker A creates transition, response lost / terminates. Fresh Worker B recovers."""
        item_id = "item-fresh-crash-recovery"
        job = self._create_job(item_id)

        # Worker A in Runner Environment 1
        storage_a = MockStorageClient(root_dir=self.storage_dir)
        storage_a.fault_crash_after_drive_creation_before_response = True

        trans = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="runner-worker-A",
            created_at=1000,
            payload={"claimed_at": 1000, "lease_timeout_ms": 900_000},
        )

        with self.assertRaises(RuntimeError) as ctx:
            storage_a.append_transition(trans)
        self.assertIn("CRASH_AFTER_DRIVE_CREATION_BEFORE_RESPONSE", str(ctx.exception))

        # Worker A environment is destroyed (storage_a dereferenced)
        del storage_a

        # Worker B starts in completely FRESH runner environment
        storage_b = MockStorageClient(root_dir=self.storage_dir)

        # Worker B attempts to emit the exact same logical transition
        recovered_ok = storage_b.append_transition(trans)
        self.assertTrue(recovered_ok, "Fresh Worker B must recover and verify the transition")

        # Exactly ONE transition file must exist on disk for this logical transition
        transitions_folder = self.storage_dir / "transitions" / item_id
        files = list(transitions_folder.glob("*.json"))
        self.assertEqual(1, len(files), f"Expected exactly 1 transition file, found: {files}")
        self.assertEqual(f"{trans.deterministic_transition_id}.json", files[0].name)

        # Folded state must be correct
        transitions = storage_b.list_transitions(item_id)
        self.assertEqual(1, len(transitions))
        folded, warnings = fold_transitions(item_id, transitions)
        self.assertEqual([], warnings)
        self.assertEqual(JobStatus.PROCESSING, folded.status)
        self.assertEqual("runner-worker-A", folded.claimed_by)
        self.assertEqual(1, folded.active_attempt_epoch)

    def test_fresh_runner_crash_immediately_after_id_generation(self):
        """Blocker 3: Runner terminates immediately after ID generation before intent persistence."""
        item_id = "item-crash-after-id-gen"
        job = self._create_job(item_id)

        storage_a = MockStorageClient(root_dir=self.storage_dir)
        storage_a.fault_crash_after_id_generation = True

        trans = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="runner-worker-A",
            created_at=1000,
            payload={"claimed_at": 1000},
        )

        with self.assertRaises(RuntimeError) as ctx:
            storage_a.append_transition(trans)
        self.assertIn("CRASH_AFTER_ID_GENERATION", str(ctx.exception))
        del storage_a

        # Fresh Runner B starts: no intent exists, generates fresh ID and succeeds
        storage_b = MockStorageClient(root_dir=self.storage_dir)
        ok = storage_b.append_transition(trans)
        self.assertTrue(ok)

        transitions = storage_b.list_transitions(item_id)
        self.assertEqual(1, len(transitions))
        self.assertEqual(trans.deterministic_transition_id, transitions[0].deterministic_transition_id)

    def test_fresh_runner_crash_after_intent_persistence_before_create(self):
        """Blocker 3: Runner terminates after intent persistence but before files.create."""
        item_id = "item-crash-after-intent"
        job = self._create_job(item_id)

        storage_a = MockStorageClient(root_dir=self.storage_dir)
        storage_a.fault_crash_after_intent_persistence = True

        trans = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="runner-worker-A",
            created_at=1000,
            payload={"claimed_at": 1000},
        )

        with self.assertRaises(RuntimeError) as ctx:
            storage_a.append_transition(trans)
        self.assertIn("CRASH_AFTER_INTENT_PERSISTENCE", str(ctx.exception))
        del storage_a

        # Intent exists on disk, but transition does not exist yet
        storage_b = MockStorageClient(root_dir=self.storage_dir)
        intent = storage_b.find_transition_intent(item_id, trans.deterministic_transition_id)
        self.assertNotNull = self.assertIsNotNone(intent)
        self.assertTrue(intent["drive_file_id"].startswith("mock_drive_id_"))

        # Fresh Runner B reuses the persisted intent Drive file ID
        ok = storage_b.append_transition(trans)
        self.assertTrue(ok)

        # Transition exists and intent is cleaned up
        transitions = storage_b.list_transitions(item_id)
        self.assertEqual(1, len(transitions))
        self.assertIsNone(storage_b.find_transition_intent(item_id, trans.deterministic_transition_id))

    def test_fresh_runner_crash_after_transition_before_projection_publish(self):
        """Blocker 3: Runner terminates after transition is written but before projection publication."""
        item_id = "item-crash-before-projection"
        job = self._create_job(item_id)

        storage_a = MockStorageClient(root_dir=self.storage_dir)
        trans = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="runner-worker-A",
            created_at=1000,
            payload={"claimed_at": 1000},
        )
        storage_a.append_transition(trans)
        # Runner A dies before updating jobs/{item_id}.json
        del storage_a

        # Fresh Runner B starts: projection on disk is still QUEUED, but transition log has CLAIM
        storage_b = MockStorageClient(root_dir=self.storage_dir)
        stale_proj = storage_b.get_job(item_id)
        self.assertEqual(JobStatus.QUEUED, stale_proj.status)

        # Rebuilding projection restores authoritative state
        rebuilt = storage_b.rebuild_projection(item_id)
        self.assertEqual(JobStatus.PROCESSING, rebuilt.status)
        self.assertEqual("runner-worker-A", rebuilt.claimed_by)

    def test_409_conflict_with_mismatched_payload_rejected_as_fork(self):
        """Blocker 1: An arbitrary 409 is NEVER treated as proof of success if payload differs."""
        item_id = "item-409-mismatch"
        job = self._create_job(item_id)

        storage = MockStorageClient(root_dir=self.storage_dir)
        trans = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="runner-A",
            created_at=1000,
            payload={"claimed_at": 1000},
        )

        filename = f"{trans.deterministic_transition_id}.json"
        storage.simulated_upload_conflicts[filename] = True

        # Pre-seed conflicting transition with different payload
        conflict_trans = TransitionRecord(
            item_id=item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="runner-CONFLICT",
            created_at=1000,
            payload={"claimed_at": 9999},
        )
        target_dir = storage.transitions_dir / item_id
        target_dir.mkdir(parents=True, exist_ok=True)
        (target_dir / filename).write_text(conflict_trans.to_json(), encoding="utf-8")

        # Now attempting to append trans must be REJECTED (returns False), not accepted blindly
        res = storage.append_transition(trans)
        self.assertFalse(res, "409 with conflicting payload/identity must be rejected")


if __name__ == "__main__":
    unittest.main()
