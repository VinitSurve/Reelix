"""Unit tests for the end-to-end cloud processing pipeline orchestrator."""

import shutil
import time
import unittest
from unittest.mock import MagicMock, patch
from pathlib import Path
from typing import Optional

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
from workers.processor.src.stages.downloader import (
    DownloadResult,
    DownloadStatus,
    MediaDownloader,
)
from workers.processor.src.stages.gemini_analyzer import GeminiAnalysisOutcome
from workers.processor.src.stages.ocr_extractor import OcrExtractionOutcome
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


class FakeOcrExtractor:
    def __init__(self, text: str = "Extracted on-screen OCR text"):
        self.text = text
        self.called = 0

    def extract(self, input_video: Path, output_dir: Path, max_frames: int = 3, timeout_seconds: int = 60):
        self.called += 1
        return OcrExtractionOutcome(success=True, text=self.text, frames_processed=2)


class FakeAnalyzer:
    def __init__(self, success: bool = True, is_retryable: bool = True, error: str = "Gemini quota exceeded"):
        self.success = success
        self.is_retryable = is_retryable
        self.error = error
        self.called = 0
        self.last_ocr_text = None
        self.last_category = None
        self.last_user_tags = None

    def analyze(self, title=None, caption=None, transcript=None, notes=None, ocr_text=None, category=None, user_tags=None, **kwargs):
        self.called += 1
        self.last_ocr_text = ocr_text
        self.last_category = category
        self.last_user_tags = user_tags
        if not self.success:
            return GeminiAnalysisOutcome(success=False, error=self.error, is_retryable=self.is_retryable)
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


class FakeDownloader(MediaDownloader):
    def __init__(self, outcome: Optional[DownloadResult] = None):
        self.outcome = outcome
        self.called = 0
        self.last_url = None

    def download(self, url: str, output_dir: Path, item_id: str) -> DownloadResult:
        self.called += 1
        self.last_url = url
        if self.outcome is not None:
            return self.outcome

        # Default success: create dummy file on disk
        output_dir.mkdir(parents=True, exist_ok=True)
        media_path = output_dir / f"{item_id}_dummy.mp4"
        media_path.write_bytes(b"dummy downloaded video bytes")
        return DownloadResult(
            status=DownloadStatus.SUCCESS,
            media_path=media_path,
            mime_type="video/mp4",
            file_name=media_path.name,
            size_bytes=len(b"dummy downloaded video bytes"),
            duration_ms=10000,
            title="Downloaded Title",
            caption="Downloaded Caption",
            provider="fake-downloader",
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
        self.assertIsNotNone(j1.next_retry_at)

        # Advance backoff timer so retry is due
        j1.next_retry_at = 0
        self.storage.update_job(j1)

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

    def test_competing_worker_losing_lease_mid_pipeline_aborts_without_overwriting(self):
        job = JobManifest(
            item_id="item-lease-stolen-test",
            source="URL",
            status=JobStatus.QUEUED,
            caption="Post for mid-flight preemption testing",
            created_at=1000,
            updated_at=1000,
            stages=StageStates(
                audio_extraction=StageStatus.SUCCEEDED,
                transcription=StageStatus.SUCCEEDED,
                gemini_analysis=StageStatus.PENDING,
            ),
        )
        self.storage.update_job(job)

        class PreemptingAnalyzer(FakeAnalyzer):
            def __init__(self, storage, item_id, competing_runner):
                super().__init__(success=True)
                self.storage = storage
                self.item_id = item_id
                self.competing_runner = competing_runner

            def analyze(self, *args, **kwargs):
                # Simulate timeout: competing runner reclaims the lease while slow worker was analyzing
                stolen = self.storage.get_job(self.item_id)
                stolen.claimed_by = self.competing_runner
                job_file = self.storage.jobs_dir / f"{self.item_id}.json"
                job_file.write_text(stolen.to_json(), encoding="utf-8")
                return super().analyze(*args, **kwargs)

        pipeline_slow = CloudProcessingPipeline(
            config=WorkerConfig(work_dir=self.test_dir / "work_slow", runner_id="runner-SLOW"),
            storage=self.storage,
            gemini_analyzer=PreemptingAnalyzer(self.storage, "item-lease-stolen-test", "runner-NEW"),
        )

        job_slow = self.storage.get_job("item-lease-stolen-test")
        result = pipeline_slow.process_job(job_slow)
        self.assertFalse(result, "Slow worker must abort when lease was reclaimed by runner-NEW")

        # Confirm runner-NEW still owns the job and runner-SLOW did NOT overwrite it with COMPLETED
        final_job = self.storage.get_job("item-lease-stolen-test")
        self.assertEqual("runner-NEW", final_job.claimed_by)
        self.assertNotEqual(JobStatus.COMPLETED, final_job.status)

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
        fake_dl = FakeDownloader(DownloadResult(status=DownloadStatus.UNAVAILABLE, error_message="Not found"))
        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=fake_analyzer,
            downloader=fake_dl,
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
        self.assertEqual(StageStatus.SKIPPED, updated.stages.acquisition)
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
        fake_dl = FakeDownloader(DownloadResult(status=DownloadStatus.UNAVAILABLE, error_message="Private post"))
        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=fake_analyzer,
            downloader=fake_dl,
        )

        success = pipeline.process_job(job)
        self.assertTrue(success)

        updated = self.storage.get_job("item-url-with-caption")
        self.assertEqual(JobStatus.COMPLETED, updated.status)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.acquisition)
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
        fake_dl = FakeDownloader(DownloadResult(status=DownloadStatus.UNAVAILABLE, error_message="Private post"))
        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=fake_analyzer,
            downloader=fake_dl,
        )

        success = pipeline.process_job(job)
        self.assertTrue(success)

        updated = self.storage.get_job("item-url-with-notes")
        self.assertEqual(JobStatus.COMPLETED, updated.status)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.acquisition)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.audio_extraction)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.transcription)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.gemini_analysis)
        self.assertEqual(1, fake_analyzer.called)
        self.assertIsNotNone(updated.results.analysis)

    def test_uploaded_media_preserves_existing_behavior(self):
        media_file = self.test_dir / "uploaded.mp4"
        media_file.write_bytes(b"uploaded video bytes")
        self.storage.register_media("uploaded-drive-id-1", media_file)

        job = JobManifest(
            item_id="item-uploaded-pre-existing",
            source="MEDIA_SHARE",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=MediaInfo(
                file_name="uploaded.mp4",
                mime_type="video/mp4",
                drive_file_id="uploaded-drive-id-1",
                size_bytes=20,
            ),
        )
        self.storage.update_job(job)

        fake_dl = FakeDownloader()
        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            audio_extractor=FakeExtractor(),
            transcriber=FakeTranscriber(),
            gemini_analyzer=FakeAnalyzer(),
            downloader=fake_dl,
        )

        success = pipeline.process_job(job)
        self.assertTrue(success)
        self.assertEqual(0, fake_dl.called, "Downloader must not be called when media is pre-existing")

        updated = self.storage.get_job("item-uploaded-pre-existing")
        self.assertEqual(JobStatus.COMPLETED, updated.status)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.acquisition)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.audio_extraction)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.transcription)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.gemini_analysis)

    def test_url_job_successful_acquisition_and_full_pipeline(self):
        job = JobManifest(
            item_id="item-acquire-success",
            source="SHARE",
            original_url="https://www.instagram.com/reel/SuccessfulAcquire/",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=None,
        )
        self.storage.update_job(job)

        fake_dl = FakeDownloader()  # Returns SUCCESS with dummy file
        fake_extractor = FakeExtractor()
        fake_transcriber = FakeTranscriber()
        fake_analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            audio_extractor=fake_extractor,
            transcriber=fake_transcriber,
            gemini_analyzer=fake_analyzer,
            downloader=fake_dl,
        )

        success = pipeline.process_job(job)
        self.assertTrue(success)
        self.assertEqual(1, fake_dl.called)
        self.assertEqual(1, fake_extractor.called)
        self.assertEqual(1, fake_transcriber.called)
        self.assertEqual(1, fake_analyzer.called)

        updated = self.storage.get_job("item-acquire-success")
        self.assertEqual(JobStatus.COMPLETED, updated.status)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.acquisition)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.audio_extraction)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.transcription)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.gemini_analysis)
        self.assertIsNotNone(updated.media)
        self.assertTrue(updated.media.drive_file_id.startswith("media_item-acquire-success"))
        self.assertEqual("Transcribed speech content", updated.results.transcript)
        self.assertIsNotNone(updated.results.analysis)

    def test_url_job_media_unavailable_falls_back_to_caption_gemini(self):
        job = JobManifest(
            item_id="item-unavail-with-caption",
            source="SHARE",
            original_url="https://www.instagram.com/reel/PrivateReel/",
            caption="Valuable caption text discussing Kotlin Coroutines",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=None,
        )
        self.storage.update_job(job)

        fake_dl = FakeDownloader(
            DownloadResult(
                status=DownloadStatus.UNAVAILABLE,
                error_message="Instagram: User account is private",
                is_retryable=False,
            )
        )
        fake_extractor = FakeExtractor()
        fake_transcriber = FakeTranscriber()
        fake_analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            audio_extractor=fake_extractor,
            transcriber=fake_transcriber,
            gemini_analyzer=fake_analyzer,
            downloader=fake_dl,
        )

        success = pipeline.process_job(job)
        self.assertTrue(success)
        self.assertEqual(1, fake_dl.called)
        self.assertEqual(0, fake_extractor.called, "Audio extraction must be skipped when media unavailable")
        self.assertEqual(0, fake_transcriber.called, "Transcription must be skipped when media unavailable")
        self.assertEqual(1, fake_analyzer.called, "Gemini should analyze available caption")

        updated = self.storage.get_job("item-unavail-with-caption")
        self.assertEqual(JobStatus.COMPLETED, updated.status)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.acquisition)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.audio_extraction)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.transcription)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.gemini_analysis)
        self.assertEqual("https://www.instagram.com/reel/PrivateReel/", updated.original_url)
        self.assertEqual("Valuable caption text discussing Kotlin Coroutines", updated.caption)

    def test_url_job_transient_failure_retries_with_backoff(self):
        job = JobManifest(
            item_id="item-transient-rate-limit",
            source="SHARE",
            original_url="https://www.instagram.com/reel/RateLimitedReel/",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            retry_count=0,
            max_retries=2,
            media=None,
        )
        self.storage.update_job(job)

        fake_dl = FakeDownloader(
            DownloadResult(
                status=DownloadStatus.TRANSIENT_FAILURE,
                error_message="HTTP Error 429: Too Many Requests",
                is_retryable=True,
            )
        )

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            downloader=fake_dl,
        )

        success = pipeline.process_job(job)
        self.assertFalse(success, "Transient failure returns False to trigger retry handling")

        updated = self.storage.get_job("item-transient-rate-limit")
        self.assertEqual(JobStatus.WAITING_RETRY, updated.status)
        self.assertEqual(1, updated.retry_count)
        self.assertIn("Temporary media acquisition restriction", updated.error_message)

    def test_old_manifest_compatibility_in_pipeline(self):
        # Register media file
        legacy_media = self.test_dir / "legacy.mp4"
        legacy_media.write_bytes(b"legacy media content")
        self.storage.register_media("legacy-drive-id", legacy_media)

        # Legacy manifest JSON string without "acquisition" key in stages
        old_json = """
        {
          "version": 1,
          "item_id": "item-legacy-pipeline",
          "source": "MEDIA_SHARE",
          "status": "QUEUED",
          "created_at": 1000,
          "updated_at": 1000,
          "media": {
            "file_name": "legacy.mp4",
            "mime_type": "video/mp4",
            "drive_file_id": "legacy-drive-id",
            "size_bytes": 20
          },
          "stages": {
            "audio_extraction": "PENDING",
            "transcription": "PENDING",
            "gemini_analysis": "PENDING"
          },
          "results": {}
        }
        """
        legacy_job = JobManifest.from_json(old_json)
        self.storage.update_job(legacy_job)

        fake_dl = FakeDownloader()
        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            audio_extractor=FakeExtractor(),
            transcriber=FakeTranscriber(),
            gemini_analyzer=FakeAnalyzer(),
            downloader=fake_dl,
        )

        success = pipeline.process_job(legacy_job)
        self.assertTrue(success)
        self.assertEqual(0, fake_dl.called)

        updated = self.storage.get_job("item-legacy-pipeline")
        self.assertEqual(JobStatus.COMPLETED, updated.status)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.acquisition)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.audio_extraction)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.transcription)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.gemini_analysis)

    def test_pipeline_executes_ocr_and_forwards_user_metadata(self):
        media_file = self.test_dir / "recipe.mp4"
        media_file.write_bytes(b"dummy video bytes")
        self.storage.register_media("recipe-drive-id", media_file)

        job = JobManifest(
            item_id="item-recipe-ocr",
            source="MEDIA_SHARE",
            category="Culinary",
            user_tags=["cooking", "pasta"],
            user_notes="Try this for dinner",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=MediaInfo(
                file_name="recipe.mp4",
                mime_type="video/mp4",
                drive_file_id="recipe-drive-id",
                size_bytes=100,
            ),
        )
        self.storage.update_job(job)

        fake_ocr = FakeOcrExtractor(text="Ingredients: 200g Flour, 2 Eggs")
        fake_analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            audio_extractor=FakeExtractor(),
            transcriber=FakeTranscriber(),
            gemini_analyzer=fake_analyzer,
            ocr_extractor=fake_ocr,
        )

        success = pipeline.process_job(job)
        self.assertTrue(success)
        self.assertEqual(1, fake_ocr.called)
        self.assertEqual("Ingredients: 200g Flour, 2 Eggs", fake_analyzer.last_ocr_text)
        self.assertEqual("Culinary", fake_analyzer.last_category)
        self.assertEqual(["cooking", "pasta"], fake_analyzer.last_user_tags)

        updated = self.storage.get_job("item-recipe-ocr")
        self.assertEqual(JobStatus.COMPLETED, updated.status)
        self.assertEqual("Ingredients: 200g Flour, 2 Eggs", updated.results.ocr_text)
        self.assertEqual("Culinary", updated.category)
        self.assertEqual(["cooking", "pasta"], updated.user_tags)

    def test_permanent_authentication_failure_pipeline_fails_immediately_without_retry(self):
        job = JobManifest(
            item_id="item-perm-fail-test",
            source="URL",
            caption="Test post for permanent failure",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            stages=StageStates(
                acquisition=StageStatus.SKIPPED,
                audio_extraction=StageStatus.SKIPPED,
                transcription=StageStatus.SKIPPED,
                gemini_analysis=StageStatus.PENDING,
            ),
        )
        self.storage.update_job(job)

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=FakeAnalyzer(
                success=False,
                is_retryable=False,
                error="Gemini API authentication failed (HTTP 401): invalid API key",
            ),
        )

        success = pipeline.process_job(job)
        self.assertFalse(success)

        updated = self.storage.get_job("item-perm-fail-test")
        self.assertEqual(JobStatus.FAILED, updated.status)
        self.assertEqual(0, updated.retry_count, "Permanent failure must not increment retry count")
        self.assertIsNone(updated.next_retry_at)
        self.assertIn("authentication failed", updated.error_message)

    def test_transient_failure_sets_waiting_retry_with_backoff(self):
        job = JobManifest(
            item_id="item-transient-backoff-test",
            source="URL",
            caption="Test post for transient backoff",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            stages=StageStates(
                acquisition=StageStatus.SKIPPED,
                audio_extraction=StageStatus.SKIPPED,
                transcription=StageStatus.SKIPPED,
                gemini_analysis=StageStatus.PENDING,
            ),
        )
        self.storage.update_job(job)

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            gemini_analyzer=FakeAnalyzer(
                success=False,
                is_retryable=True,
                error="Gemini HTTP 503: High demand",
            ),
        )

        now_before = int(time.time() * 1000)
        success = pipeline.process_job(job)
        self.assertFalse(success)

        updated = self.storage.get_job("item-transient-backoff-test")
        self.assertEqual(JobStatus.WAITING_RETRY, updated.status)
        self.assertEqual(1, updated.retry_count)
        self.assertIsNotNone(updated.next_retry_at)
        # next_retry_at should be in the future (between 40s and 80s for 60s base backoff with jitter)
        self.assertGreater(updated.next_retry_at, now_before + 30_000)

    def test_waiting_retry_respects_next_retry_at_delay(self):
        now_ms = int(time.time() * 1000)
        # Job 1: retry due in the future
        future_job = JobManifest(
            item_id="item-retry-future",
            source="URL",
            caption="Post not yet due",
            status=JobStatus.WAITING_RETRY,
            next_retry_at=now_ms + 600_000,  # 10 minutes from now
            created_at=now_ms - 1000,
            updated_at=now_ms - 500,
        )
        self.storage.update_job(future_job)

        # Job 2: retry already due (in the past)
        due_job = JobManifest(
            item_id="item-retry-due",
            source="URL",
            caption="Post ready to retry",
            status=JobStatus.WAITING_RETRY,
            next_retry_at=now_ms - 5_000,  # 5 seconds ago
            created_at=now_ms - 10_000,
            updated_at=now_ms - 6_000,
        )
        self.storage.update_job(due_job)

        queued = self.storage.list_queued_jobs(limit=10)
        queued_ids = [j.item_id for j in queued]
        self.assertNotIn("item-retry-future", queued_ids, "Future retry job must not be picked up")
        self.assertIn("item-retry-due", queued_ids, "Due retry job must be picked up")

    def test_pipeline_with_firestore_coordinator_e2e(self):
        from workers.processor.src.storage.firestore_client import FirestoreJobCoordinator
        from workers.processor.tests.test_firestore_coordinator import MockFirestoreClient

        mock_firestore = MockFirestoreClient()
        coordinator = FirestoreJobCoordinator(client=mock_firestore)

        # Create job in firestore and storage
        job = JobManifest(
            item_id="item-firestore-hybrid",
            source="SHARE",
            original_url="https://www.instagram.com/reel/HybridTest/",
            caption="Instagram reel on Clean Architecture",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
        )
        coordinator.submit_job(job)
        self.storage.update_job(job)

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            coordinator=coordinator,
            audio_extractor=FakeExtractor(),
            transcriber=FakeTranscriber(),
            gemini_analyzer=FakeAnalyzer(),
            downloader=FakeDownloader(),
            ocr_extractor=FakeOcrExtractor(),
        )

        summary = pipeline.run_batch()
        self.assertEqual(1, summary.total_found)
        self.assertEqual(1, summary.succeeded)

        # Check firestore completed state
        stored_firestore = coordinator.get_job("item-firestore-hybrid")
        self.assertIsNotNone(stored_firestore)
        self.assertEqual(JobStatus.COMPLETED, stored_firestore.status)

        # Check storage completed state
        stored_storage = self.storage.get_job("item-firestore-hybrid")
        self.assertIsNotNone(stored_storage)
        self.assertEqual(JobStatus.COMPLETED, stored_storage.status)

    def test_main_cli_mac_local_and_worker_id(self):
        from workers.processor.src.main import main, parse_args
        args = parse_args(["--mac-local", "--worker-id", "custom-mac-worker-99", "--limit", "3"])
        self.assertTrue(args.confirm_local)
        self.assertEqual("custom-mac-worker-99", args.worker_id)
        self.assertEqual(3, args.limit)

    def test_pipeline_heartbeat_renewal_during_execution(self):
        job = JobManifest(
            item_id="item-hb-test",
            source="URL",
            original_url="https://www.instagram.com/reel/HeartbeatTest/",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=None,
        )
        self.storage.update_job(job)

        with patch.object(self.storage, "renew_lease", wraps=self.storage.renew_lease) as mock_renew:
            pipeline = CloudProcessingPipeline(
                config=self.config,
                storage=self.storage,
                audio_extractor=FakeExtractor(),
                transcriber=FakeTranscriber(),
                gemini_analyzer=FakeAnalyzer(),
                downloader=FakeDownloader(),
            )
            success = pipeline.process_job(job)
            self.assertTrue(success)
            self.assertGreaterEqual(mock_renew.call_count, 1)

    def test_pipeline_aborts_immediately_on_heartbeat_lease_loss(self):
        job = JobManifest(
            item_id="item-lease-loss",
            source="URL",
            original_url="https://www.instagram.com/reel/LeaseLossTest/",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=None,
        )
        self.storage.update_job(job)

        fake_transcriber = FakeTranscriber()
        call_count = [0]
        original_renew = self.storage.renew_lease

        def controlled_renew(job_id, worker_id, lease_timeout_ms=900_000, job=None):
            call_count[0] += 1
            if call_count[0] >= 3:  # Fail on third heartbeat (before transcription)
                return False
            return original_renew(job_id, worker_id, lease_timeout_ms, job)

        with patch.object(self.storage, "renew_lease", side_effect=controlled_renew):
            pipeline = CloudProcessingPipeline(
                config=self.config,
                storage=self.storage,
                audio_extractor=FakeExtractor(),
                transcriber=fake_transcriber,
                gemini_analyzer=FakeAnalyzer(),
                downloader=FakeDownloader(),
            )
            success = pipeline.process_job(job)
            self.assertFalse(success, "Pipeline must abort immediately when lease renewal fails")
            self.assertEqual(0, fake_transcriber.called, "Transcription must not be executed after lease loss")

    def test_stage_details_and_artifacts_recorded_accurately(self):
        job = JobManifest(
            item_id="item-stage-details",
            source="URL",
            original_url="https://www.instagram.com/reel/StageDetailsTest/",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
            media=None,
        )
        self.storage.update_job(job)

        pipeline = CloudProcessingPipeline(
            config=self.config,
            storage=self.storage,
            audio_extractor=FakeExtractor(),
            transcriber=FakeTranscriber(),
            gemini_analyzer=FakeAnalyzer(),
            downloader=FakeDownloader(),
        )
        success = pipeline.process_job(job)
        self.assertTrue(success)

        updated = self.storage.get_job("item-stage-details")
        self.assertEqual(JobStatus.COMPLETED, updated.status)

        acq_detail = updated.get_stage_detail("acquisition")
        self.assertEqual(StageStatus.SUCCEEDED, acq_detail.status)
        self.assertGreaterEqual(acq_detail.attempts, 1)
        self.assertIsNotNone(acq_detail.completed_at)

        audio_detail = updated.get_stage_detail("audio_extraction")
        self.assertEqual(StageStatus.SUCCEEDED, audio_detail.status)
        self.assertGreaterEqual(audio_detail.attempts, 1)

        gemini_detail = updated.get_stage_detail("gemini_analysis")
        self.assertEqual(StageStatus.SUCCEEDED, gemini_detail.status)
        self.assertGreaterEqual(gemini_detail.attempts, 1)

        # Artifacts recorded
        self.assertIn("acquisition", updated.artifacts)
        self.assertIn("audio_extraction", updated.artifacts)

    def test_main_cli_fails_if_firestore_without_experimental_flag(self):
        from workers.processor.src.main import main
        import os

        with patch.dict(os.environ, {"FIRESTORE_PROJECT_ID": "accidental-firestore-project", "REELIX_MOCK_MODE": "1"}):
            # Without --experimental-firestore, must fail with exit code 1
            code = main([])
            self.assertEqual(1, code, "Must exit 1 if FIRESTORE_PROJECT_ID is present without --experimental-firestore")

            # With --experimental-firestore, permitted
            code_experimental = main(["--experimental-firestore"])
            self.assertEqual(0, code_experimental)

    def test_process_job_aborts_if_coordinator_storage_claim_fails(self):
        job = JobManifest(
            item_id="item-coord-storage-fail",
            source="URL",
            status=JobStatus.QUEUED,
            caption="Test manifest for coordinator claim",
            created_at=1000,
            updated_at=1000,
        )
        self.storage.update_job(job)

        mock_coordinator = MagicMock()
        mock_coordinator.claim_job.return_value = job

        # Mock storage claim_job to fail (e.g. concurrent claim or network error)
        with patch.object(self.storage, "claim_job", return_value=False):
            pipeline = CloudProcessingPipeline(
                config=self.config,
                storage=self.storage,
                coordinator=mock_coordinator,
            )
            success = pipeline.process_job(job)
            self.assertFalse(success, "Must return False when storage claim fails even if coordinator succeeded")

    def test_continuous_heartbeat_background_thread_renews_periodically(self):
        from workers.processor.src.pipeline import ContinuousHeartbeatManager
        now_ms = int(time.time() * 1000)
        job = JobManifest(
            item_id="item-thread-hb",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="test-runner",
            claimed_at=now_ms,
            lease_timeout_ms=5000,
            created_at=now_ms,
            updated_at=now_ms,
        )
        self.storage.update_job(job)

        with patch.object(self.storage, "renew_lease", wraps=self.storage.renew_lease) as mock_renew:
            mgr = ContinuousHeartbeatManager(
                storage=self.storage,
                job=job,
                runner_id="test-runner",
                lease_timeout_ms=5000,
                interval_seconds=0.1,  # Fast 100ms interval for test
            )
            with mgr:
                time.sleep(0.35)  # Should trigger at least 2-3 background renewals
            self.assertGreaterEqual(mock_renew.call_count, 2)
            self.assertFalse(mgr.is_cancelled)

    def test_job_completes_honestly_when_ai_analysis_skipped_or_unconfigured(self):
        """Verify that when media and audio are processed, but Gemini is unconfigured or skipped,
        the job successfully completes as 'Processed without AI analysis' rather than failing."""
        job = JobManifest(
            item_id="item-no-ai-complete",
            source="URL",
            original_url="https://www.instagram.com/reel/NoAiComplete/",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
        )
        self.storage.update_job(job)

        no_ai_config = WorkerConfig(
            mock_mode=True,
            gemini_api_key=None,
            runner_id="test-runner",
        )

        unconfigured_analyzer = FakeAnalyzer(
            success=False,
            is_retryable=False,
            error="GEMINI_API_KEY is not configured",
        )

        pipeline = CloudProcessingPipeline(
            config=no_ai_config,
            storage=self.storage,
            downloader=FakeDownloader(),
            audio_extractor=FakeExtractor(),
            transcriber=FakeTranscriber(),
            gemini_analyzer=unconfigured_analyzer,
        )

        success = pipeline.process_job(job)
        self.assertTrue(success, "Pipeline must succeed when AI analysis is skipped/unconfigured")

        updated = self.storage.get_job("item-no-ai-complete")
        self.assertEqual(JobStatus.COMPLETED, updated.status)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.acquisition)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.audio_extraction)
        self.assertEqual(StageStatus.SUCCEEDED, updated.stages.transcription)
        self.assertEqual(StageStatus.SKIPPED, updated.stages.gemini_analysis)
        self.assertIsNotNone(updated.results.transcript)
        self.assertIsNone(updated.results.analysis)


if __name__ == "__main__":
    unittest.main()


