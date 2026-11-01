"""Unit tests for the end-to-end cloud processing pipeline orchestrator."""

import shutil
import unittest
from pathlib import Path

from workers.processor.src.config import WorkerConfig
from workers.processor.src.models import (
    GeminiAnalysisResult,
    JobManifest,
    JobStatus,
    MediaInfo,
    StageStates,
    StageStatus,
)
from workers.processor.src.pipeline import CloudProcessingPipeline
from workers.processor.src.stages.audio_extractor import AudioExtractionOutcome
from workers.processor.src.stages.gemini_analyzer import GeminiAnalysisOutcome
from workers.processor.src.stages.transcriber import TranscriptionOutcome
from workers.processor.src.storage.mock_storage import MockStorageClient


class FakeExtractor:
    def __init__(self, success: bool = True, no_audio: bool = False):
        self.success = success
        self.no_audio = no_audio
        self.called = 0

    def extract(self, input_video: Path, output_wav: Path, timeout_seconds: int = 180):
        self.called += 1
        if self.no_audio:
            return AudioExtractionOutcome(success=False, no_audio_track=True, error="No audio")
        if not self.success:
            return AudioExtractionOutcome(success=False, error="Extraction failed")
        output_wav.write_bytes(b"dummy wav data")
        return AudioExtractionOutcome(success=True, wav_path=output_wav, size_bytes=100)


class FakeTranscriber:
    def __init__(self, success: bool = True):
        self.success = success
        self.called = 0

    def transcribe(self, wav_path: Path):
        self.called += 1
        if not self.success:
            return TranscriptionOutcome(success=False, error="Whisper failed")
        return TranscriptionOutcome(success=True, transcript="Transcribed speech content", model_name="base")


class FakeAnalyzer:
    def __init__(self, success: bool = True):
        self.success = success
        self.called = 0

    def analyze(self, title=None, caption=None, transcript=None, notes=None):
        self.called += 1
        if not self.success:
            return GeminiAnalysisOutcome(success=False, error="Gemini quota exceeded")
        return GeminiAnalysisOutcome(
            success=True,
            result=GeminiAnalysisResult(
                summary="AI summary",
                detailed_summary="AI detailed summary",
                main_topic="Topic",
                category="General",
                keywords=["k1", "k2"],
                tags=["t1"],
                key_takeaways=["takeaway1"],
                important_concepts=["concept1"],
                actionable_steps=["step1"],
                model_name="gemini-3.6-flash",
                generated_at=1000,
            ),
        )


class TestPipeline(unittest.TestCase):

    def setUp(self):
        self.test_dir = Path("/tmp/test_reelix_pipeline")
        self.test_dir.mkdir(parents=True, exist_ok=True)
        self.config = WorkerConfig(
            work_dir=self.test_dir / "work",
            runner_id="test-runner",
            max_jobs_per_run=5,
        )
        self.storage = MockStorageClient(root_dir=self.test_dir / "storage")

    def tearDown(self):
        if self.test_dir.exists():
            shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_end_to_end_job_processing_success(self):
        # 1. Register fake media in mock storage
        media_file = self.test_dir / "input.mp4"
        media_file.write_bytes(b"video bytes")
        self.storage.register_media("media-file-123", media_file)

        # 2. Put queued job into mock storage
        job = JobManifest(
            item_id="item-pipeline-1",
            source="MEDIA_SHARE",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=MediaInfo(
                file_name="input.mp4",
                mime_type="video/mp4",
                drive_file_id="media-file-123",
                size_bytes=100,
            ),
        )
        self.storage.update_job(job)

        extractor = FakeExtractor()
        transcriber = FakeTranscriber()
        analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=analyzer,
        )

        summary = pipeline.run_batch()
        self.assertEqual(1, summary.total_found)
        self.assertEqual(1, summary.succeeded)
        self.assertEqual(0, summary.failed)

        # Check job in storage
        completed_job = self.storage.get_job("item-pipeline-1")
        self.assertIsNotNone(completed_job)
        self.assertEqual(JobStatus.COMPLETED, completed_job.status)
        self.assertEqual(StageStatus.SUCCEEDED, completed_job.stages.audio_extraction)
        self.assertEqual(StageStatus.SUCCEEDED, completed_job.stages.transcription)
        self.assertEqual(StageStatus.SUCCEEDED, completed_job.stages.gemini_analysis)
        self.assertEqual("Transcribed speech content", completed_job.results.transcript)
        self.assertIsNotNone(completed_job.results.analysis)
        self.assertEqual("Topic", completed_job.results.analysis.main_topic)

        # Verify stages were each called once
        self.assertEqual(1, extractor.called)
        self.assertEqual(1, transcriber.called)
        self.assertEqual(1, analyzer.called)

    def test_job_failure_schedules_retry_until_max_retries(self):
        job = JobManifest(
            item_id="item-retry-test",
            source="URL",
            status=JobStatus.QUEUED,
            caption="Valid post caption about design systems",
            created_at=1000,
            updated_at=1000,
            retry_count=0,
            max_retries=2,
            stages=StageStates(
                audio_extraction=StageStatus.SKIPPED,
                transcription=StageStatus.SKIPPED,
                gemini_analysis=StageStatus.PENDING,
            ),
        )
        self.storage.update_job(job)

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=FakeAnalyzer(success=False),
        )

        # Attempt 1 -> WAITING_RETRY (retry_count=1)
        summary1 = pipeline.run_batch()
        self.assertEqual(1, summary1.failed)
        j1 = self.storage.get_job("item-retry-test")
        self.assertEqual(JobStatus.WAITING_RETRY, j1.status)
        self.assertEqual(1, j1.retry_count)

        # Attempt 2 -> FAILED (retry_count=2 reached max_retries=2 with zero results)
        summary2 = pipeline.run_batch()
        self.assertEqual(1, summary2.failed)
        j2 = self.storage.get_job("item-retry-test")
        self.assertEqual(JobStatus.FAILED, j2.status)
        self.assertEqual(2, j2.retry_count)

    def test_job_failure_with_partial_results_marked_partial(self):
        # When transcription succeeded but Gemini failed after exhausting retries
        job = JobManifest(
            item_id="item-partial-test",
            source="URL",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            retry_count=1,
            max_retries=2,
            stages=StageStates(
                audio_extraction=StageStatus.SUCCEEDED,
                transcription=StageStatus.SUCCEEDED,
                gemini_analysis=StageStatus.PENDING,
            ),
        )
        job.results.transcript = "Saved transcript content"
        self.storage.update_job(job)

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=FakeAnalyzer(success=False),
        )

        summary = pipeline.run_batch()
        self.assertEqual(1, summary.failed)
        j = self.storage.get_job("item-partial-test")
        self.assertEqual(JobStatus.PARTIAL, j.status)
        self.assertEqual("Saved transcript content", j.results.transcript)

    def test_concurrent_worker_claim_collision_rejected(self):
        job = JobManifest(
            item_id="item-collision-test",
            source="URL",
            status=JobStatus.QUEUED,
            caption="Post with valid content for concurrency testing",
            created_at=1000,
            updated_at=1000,
            stages=StageStates(
                audio_extraction=StageStatus.SUCCEEDED,
                transcription=StageStatus.SUCCEEDED,
                gemini_analysis=StageStatus.PENDING,
            ),
        )
        self.storage.update_job(job)

        pipeline_a = CloudProcessingPipeline(
            config=WorkerConfig(work_dir=self.test_dir / "work_a", runner_id="runner-A"),
            storage=self.storage,
            gemini_analyzer=FakeAnalyzer(success=True),
        )
        pipeline_b = CloudProcessingPipeline(
            config=WorkerConfig(work_dir=self.test_dir / "work_b", runner_id="runner-B"),
            storage=self.storage,
            gemini_analyzer=FakeAnalyzer(success=True),
        )

        job_a = self.storage.get_job("item-collision-test")
        job_b = self.storage.get_job("item-collision-test")

        # Worker A claims the job with job_a's ETag
        claimed_a = pipeline_a.process_job(job_a)
        self.assertTrue(claimed_a)

        # Worker B tries to claim using stale ETag from before Worker A claimed it
        claimed_b = pipeline_b.process_job(job_b)
        self.assertFalse(claimed_b, "Worker B should be rejected due to ETag mismatch or active lease")

    def test_stale_processing_job_reclaimed_after_lease_expires(self):
        import time

        now_ms = int(time.time() * 1000)
        # Job marked PROCESSING 30 minutes ago (lease is 15 minutes)
        stale_job = JobManifest(
            item_id="item-stale-lease",
            source="URL",
            caption="Stale post recovered caption",
            status=JobStatus.PROCESSING,
            claimed_by="dead-runner",
            claimed_at=now_ms - (30 * 60 * 1000),
            lease_timeout_ms=15 * 60 * 1000,
            created_at=now_ms - 3600000,
            updated_at=now_ms - 1800000,
            stages=StageStates(
                audio_extraction=StageStatus.SUCCEEDED,
                transcription=StageStatus.SUCCEEDED,
                gemini_analysis=StageStatus.PENDING,
            ),
        )
        self.storage.update_job(stale_job)

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=FakeAnalyzer(success=True),
        )

        # list_queued_jobs should pick up the stale job
        queued = self.storage.list_queued_jobs(limit=5)
        self.assertTrue(any(j.item_id == "item-stale-lease" for j in queued))

        # Running batch should successfully reclaim and complete it
        summary = pipeline.run_batch()
        self.assertEqual(1, summary.succeeded)

        completed = self.storage.get_job("item-stale-lease")
        self.assertEqual(JobStatus.COMPLETED, completed.status)
        self.assertEqual(self.config.runner_id, completed.claimed_by)

    def test_active_processing_job_not_stolen(self):
        import time

        now_ms = int(time.time() * 1000)
        # Job marked PROCESSING 1 minute ago (lease is 15 minutes)
        active_job = JobManifest(
            item_id="item-active-lease",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="active-runner-xyz",
            claimed_at=now_ms - (60 * 1000),
            lease_timeout_ms=15 * 60 * 1000,
            created_at=now_ms - 120000,
            updated_at=now_ms - 60000,
            stages=StageStates(
                audio_extraction=StageStatus.SUCCEEDED,
                transcription=StageStatus.SUCCEEDED,
                gemini_analysis=StageStatus.PENDING,
            ),
        )
        self.storage.update_job(active_job)

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=FakeAnalyzer(success=True),
        )

        # list_queued_jobs should NOT pick up an active job
        queued = self.storage.list_queued_jobs(limit=5)
        self.assertFalse(any(j.item_id == "item-active-lease" for j in queued))

        # Attempting to process directly should be rejected
        claimed = pipeline.process_job(active_job)
        self.assertFalse(claimed, "Active job with valid lease must not be claimed")

    def test_url_only_job_without_metadata_marked_partial_without_retries(self):
        job = JobManifest(
            item_id="item-url-only-empty",
            source="URL",
            original_url="https://www.instagram.com/reel/xyz123/",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=None,
            caption=None,
            user_notes=None,
        )
        self.storage.update_job(job)

        fake_analyzer = FakeAnalyzer(success=True)
        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=fake_analyzer,
        )

        success = pipeline.process_job(job)
        self.assertTrue(success)

        updated = self.storage.get_job("item-url-only-empty")
        self.assertEqual(JobStatus.PARTIAL, updated.status)
        self.assertEqual(0, updated.retry_count, "Should not increment retries")
        self.assertEqual(
            "URL-only item without media or metadata; share reel video or provide notes",
            updated.error_message,
        )
        self.assertEqual(StageStatus.SKIPPED, updated.stages.audio_extraction)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.transcription)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.gemini_analysis)
        self.assertEqual(0, fake_analyzer.called, "Gemini must not be called with empty text")

    def test_url_only_job_with_caption_analyzed_successfully(self):
        job = JobManifest(
            item_id="item-url-with-caption",
            source="URL",
            original_url="https://www.instagram.com/reel/abc456/",
            caption="Jetpack Compose architecture tips and tricks for state holders",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=None,
            user_notes=None,
        )
        self.storage.update_job(job)

        fake_analyzer = FakeAnalyzer(success=True)
        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=fake_analyzer,
        )

        success = pipeline.process_job(job)
        self.assertTrue(success)

        updated = self.storage.get_job("item-url-with-caption")
        self.assertEqual(JobStatus.COMPLETED, updated.status)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.audio_extraction)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.transcription)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.gemini_analysis)
        self.assertEqual(1, fake_analyzer.called)
        self.assertIsNotNone(updated.results.analysis)
        self.assertEqual("AI summary", updated.results.analysis.summary)

    def test_url_only_job_with_user_notes_analyzed_successfully(self):
        job = JobManifest(
            item_id="item-url-with-notes",
            source="URL",
            original_url="https://www.instagram.com/reel/ghi789/",
            user_notes="Key notes on clean architecture in Android Kotlin apps",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=None,
            caption=None,
        )
        self.storage.update_job(job)

        fake_analyzer = FakeAnalyzer(success=True)
        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=fake_analyzer,
        )

        success = pipeline.process_job(job)
        self.assertTrue(success)

        updated = self.storage.get_job("item-url-with-notes")
        self.assertEqual(JobStatus.COMPLETED, updated.status)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.audio_extraction)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.transcription)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.gemini_analysis)
        self.assertEqual(1, fake_analyzer.called)
        self.assertIsNotNone(updated.results.analysis)


if __name__ == "__main__":
    unittest.main()


