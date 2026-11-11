"""Unit tests verifying failure-injection, crash recovery, and idempotent resumption.

Verifies that crashes at any stage do not corrupt manifests, do not repeat already-completed
expensive stages (acquisition, audio extraction, Whisper transcription, OCR), and recover
gracefully upon next worker execution.
"""

import shutil
import tempfile
import time
import unittest
from pathlib import Path
from typing import Optional

from workers.processor.src.config import WorkerConfig
from workers.processor.src.models import (
    ArtifactRef,
    GeminiAnalysisResult,
    JobManifest,
    JobResults,
    JobStatus,
    MediaInfo,
    StageStates,
    StageStatus,
)
from workers.processor.src.pipeline import CloudProcessingPipeline
from workers.processor.src.stages.audio_extractor import AudioExtractionOutcome
from workers.processor.src.stages.downloader import DownloadResult, DownloadStatus, MediaDownloader
from workers.processor.src.stages.gemini_analyzer import GeminiAnalysisOutcome
from workers.processor.src.stages.ocr_extractor import OcrExtractionOutcome
from workers.processor.src.stages.transcriber import TranscriptionOutcome
from workers.processor.src.storage.mock_storage import MockStorageClient


class FakeExtractor:
    def __init__(self, success: bool = True):
        self.success = success
        self.called = 0

    def extract(self, input_video: Path, output_wav: Path, timeout_seconds: int = 180, **kwargs):
        self.called += 1
        if not self.success:
            return AudioExtractionOutcome(success=False, error="Extraction failed")
        output_wav.write_bytes(b"dummy wav data for crash test")
        return AudioExtractionOutcome(success=True, wav_path=output_wav, size_bytes=30)


class FakeTranscriber:
    def __init__(self, success: bool = True):
        self.success = success
        self.called = 0

    def transcribe(self, wav_path: Path):
        self.called += 1
        if not self.success:
            return TranscriptionOutcome(success=False, error="Transcription failed")
        return TranscriptionOutcome(success=True, transcript="Durable speech transcript", model_name="base")


class FakeOcrExtractor:
    def __init__(self, text: str = "Durable visual on-screen text"):
        self.text = text
        self.called = 0

    def extract(self, input_video: Path, output_dir: Path, max_frames: int = 3, timeout_seconds: int = 60, **kwargs):
        self.called += 1
        return OcrExtractionOutcome(success=True, text=self.text, frames_processed=2)


class FakeAnalyzer:
    def __init__(self, outcomes=None):
        self.outcomes = list(outcomes) if outcomes else [GeminiAnalysisOutcome(
            success=True,
            result=GeminiAnalysisResult(
                summary="Durable AI summary",
                detailed_summary="Durable detailed AI summary",
                main_topic="Reliability",
                category="Engineering",
                keywords=["crash", "recovery"],
                tags=["durable"],
                key_takeaways=["Resumes gracefully"],
                important_concepts=["Idempotency"],
                actionable_steps=["Verify stage cache"],
                model_name="gemini-3.6-flash",
                generated_at=int(time.time()),
            )
        )]
        self.called = 0

    def analyze(self, **kwargs):
        self.called += 1
        if self.outcomes:
            return self.outcomes.pop(0)
        return GeminiAnalysisOutcome(
            success=True,
            result=GeminiAnalysisResult(
                summary="Durable AI summary",
                detailed_summary="Durable detailed AI summary",
                main_topic="Reliability",
                category="Engineering",
                keywords=["crash", "recovery"],
                tags=["durable"],
                key_takeaways=["Resumes gracefully"],
                important_concepts=["Idempotency"],
                actionable_steps=["Verify stage cache"],
                model_name="gemini-3.6-flash",
                generated_at=int(time.time()),
            )
        )


class FakeDownloader(MediaDownloader):
    def __init__(self):
        self.called = 0

    def download(self, url: str, output_dir: Path, item_id: str) -> DownloadResult:
        self.called += 1
        output_dir.mkdir(parents=True, exist_ok=True)
        media_path = output_dir / f"{item_id}_downloaded.mp4"
        media_path.write_bytes(b"downloaded media video content")
        return DownloadResult(
            status=DownloadStatus.SUCCESS,
            media_path=media_path,
            file_name=f"{item_id}_downloaded.mp4",
            mime_type="video/mp4",
            size_bytes=30,
            duration_ms=15000,
        )


class TestCrashRecovery(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.storage = MockStorageClient(root_dir=Path(self.temp_dir))
        self.config = WorkerConfig(
            mock_mode=True,
            runner_id="crash-test-worker",
            heartbeat_interval_seconds=1,
            lease_timeout_ms=5000,
        )

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _create_base_job(self, item_id: str = "item-crash-1") -> JobManifest:
        return JobManifest(
            item_id=item_id,
            source="URL",
            original_url="https://www.instagram.com/reel/CrashRecoveryTest/",
            caption="Test post with valuable notes",
            user_notes="Important developer notes",
            category="Engineering",
            user_tags=["test", "recovery"],
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
        )

    def test_crash_after_acquisition_reuses_downloaded_media(self):
        """1. Simulate crash immediately after media acquisition has succeeded.
        Verify that the next worker does NOT re-download media from Instagram."""
        job = self._create_base_job("item-crash-acquire")
        media_file = Path(self.temp_dir) / "media_file.mp4"
        media_file.write_bytes(b"persistent media content")
        media_drive_id = self.storage.upload_media(media_file, job.item_id)

        media_info = MediaInfo(file_name="media_file.mp4", mime_type="video/mp4", drive_file_id=media_drive_id, size_bytes=24, duration_ms=10000)
        job.stages.acquisition = StageStatus.SUCCEEDED
        job.media = media_info
        job.results.media_drive_file_id = media_drive_id
        job.results.media_size_bytes = 24
        job.results.media_info = media_info
        job.artifacts["media"] = ArtifactRef(drive_file_id=media_drive_id, mime_type="video/mp4", size_bytes=24)
        job.status = JobStatus.QUEUED

        downloader = FakeDownloader()
        extractor = FakeExtractor()
        transcriber = FakeTranscriber()
        ocr = FakeOcrExtractor()
        analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            storage=self.storage,
            config=self.config,
            downloader=downloader,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=analyzer,
            ocr_extractor=ocr,
        )

        ok = pipeline.process_job(job)
        self.assertTrue(ok)
        self.assertEqual(JobStatus.COMPLETED, job.status)
        self.assertEqual(0, downloader.called)
        self.assertEqual(1, extractor.called)
        self.assertEqual(1, transcriber.called)
        self.assertEqual(1, ocr.called)
        self.assertEqual(1, analyzer.called)

    def test_crash_after_media_inspection_resumes_without_reacquisition(self):
        """2. Simulate crash after media was acquired and inspected, but before audio extraction started."""
        job = self._create_base_job("item-crash-inspect")
        media_file = Path(self.temp_dir) / "media.mp4"
        media_file.write_bytes(b"inspected media content")
        media_id = self.storage.upload_media(media_file, job.item_id)

        job.stages.acquisition = StageStatus.SUCCEEDED
        job.media = MediaInfo(file_name="media.mp4", mime_type="video/mp4", drive_file_id=media_id, size_bytes=23, duration_ms=12000)
        job.results.media_drive_file_id = media_id
        job.results.media_size_bytes = 23
        job.results.media_info = job.media

        downloader = FakeDownloader()
        extractor = FakeExtractor()
        transcriber = FakeTranscriber()
        ocr = FakeOcrExtractor()
        analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            storage=self.storage,
            config=self.config,
            downloader=downloader,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=analyzer,
            ocr_extractor=ocr,
        )

        ok = pipeline.process_job(job)
        self.assertTrue(ok)
        self.assertEqual(JobStatus.COMPLETED, job.status)
        self.assertEqual(0, downloader.called)
        self.assertEqual(1, extractor.called)

    def test_crash_after_audio_extraction_reuses_extracted_wav(self):
        """3. Simulate crash after audio extraction.
        Verify that resumed worker does not re-extract audio."""
        job = self._create_base_job("item-crash-audio")
        media_file = Path(self.temp_dir) / "media.mp4"
        media_file.write_bytes(b"media bytes")
        wav_file = Path(self.temp_dir) / "audio.wav"
        wav_file.write_bytes(b"audio wav bytes")

        media_id = self.storage.upload_media(media_file, job.item_id)
        audio_id = self.storage.upload_audio(wav_file, job.item_id)

        media_info = MediaInfo(file_name="media.mp4", mime_type="video/mp4", drive_file_id=media_id, size_bytes=24, duration_ms=10000)
        job.stages.acquisition = StageStatus.SUCCEEDED
        job.media = media_info
        job.results.media_drive_file_id = media_id
        job.stages.audio_extraction = StageStatus.SUCCEEDED
        job.results.audio_drive_file_id = audio_id
        job.results.audio_size_bytes = 15
        job.artifacts["media"] = ArtifactRef(drive_file_id=media_id, mime_type="video/mp4", size_bytes=24)
        job.artifacts["audio"] = ArtifactRef(drive_file_id=audio_id, mime_type="audio/wav", size_bytes=15)
        job.status = JobStatus.QUEUED

        downloader = FakeDownloader()
        extractor = FakeExtractor()
        transcriber = FakeTranscriber()
        ocr = FakeOcrExtractor()
        analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            storage=self.storage,
            config=self.config,
            downloader=downloader,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=analyzer,
            ocr_extractor=ocr,
        )

        ok = pipeline.process_job(job)
        self.assertTrue(ok)
        self.assertEqual(JobStatus.COMPLETED, job.status)
        self.assertEqual(0, downloader.called)
        self.assertEqual(0, extractor.called)
        self.assertEqual(1, transcriber.called)
        self.assertEqual(1, analyzer.called)

    def test_crash_after_transcription_reuses_transcript(self):
        """4. Simulate crash after transcription completed.
        Verify Whisper transcription is not run again."""
        job = self._create_base_job("item-crash-transcription")
        media_file = Path(self.temp_dir) / "media.mp4"
        media_file.write_bytes(b"media bytes")
        wav_file = Path(self.temp_dir) / "audio.wav"
        wav_file.write_bytes(b"audio wav bytes")
        media_id = self.storage.upload_media(media_file, job.item_id)
        audio_id = self.storage.upload_audio(wav_file, job.item_id)

        media_info = MediaInfo(file_name="media.mp4", mime_type="video/mp4", drive_file_id=media_id, size_bytes=24, duration_ms=10000)
        job.stages.acquisition = StageStatus.SUCCEEDED
        job.media = media_info
        job.stages.audio_extraction = StageStatus.SUCCEEDED
        job.stages.transcription = StageStatus.SUCCEEDED
        job.results.transcript = "Pre-computed transcript from previous run"

        downloader = FakeDownloader()
        extractor = FakeExtractor()
        transcriber = FakeTranscriber()
        ocr = FakeOcrExtractor()
        analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            storage=self.storage,
            config=self.config,
            downloader=downloader,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=analyzer,
            ocr_extractor=ocr,
        )

        ok = pipeline.process_job(job)
        self.assertTrue(ok)
        self.assertEqual(JobStatus.COMPLETED, job.status)
        self.assertEqual(0, downloader.called)
        self.assertEqual(0, extractor.called)
        self.assertEqual(0, transcriber.called)
        self.assertEqual("Pre-computed transcript from previous run", job.results.transcript)

    def test_crash_after_ocr_reuses_visual_text(self):
        """5. Simulate crash after OCR completed.
        Verify OCR extraction is not executed again."""
        job = self._create_base_job("item-crash-ocr")
        media_file = Path(self.temp_dir) / "media.mp4"
        media_file.write_bytes(b"media bytes")
        wav_file = Path(self.temp_dir) / "audio.wav"
        wav_file.write_bytes(b"audio wav bytes")
        media_id = self.storage.upload_media(media_file, job.item_id)
        audio_id = self.storage.upload_audio(wav_file, job.item_id)

        media_info = MediaInfo(file_name="media.mp4", mime_type="video/mp4", drive_file_id=media_id, size_bytes=24, duration_ms=10000)
        job.stages.acquisition = StageStatus.SUCCEEDED
        job.media = media_info
        job.stages.audio_extraction = StageStatus.SUCCEEDED
        job.stages.transcription = StageStatus.SUCCEEDED
        job.results.transcript = "Transcript"
        job.stages.ocr = StageStatus.SUCCEEDED
        job.results.ocr_text = "Pre-computed OCR visual text"

        downloader = FakeDownloader()
        extractor = FakeExtractor()
        transcriber = FakeTranscriber()
        ocr = FakeOcrExtractor()
        analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            storage=self.storage,
            config=self.config,
            downloader=downloader,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=analyzer,
            ocr_extractor=ocr,
        )

        ok = pipeline.process_job(job)
        self.assertTrue(ok)
        self.assertEqual(0, ocr.called)
        self.assertEqual("Pre-computed OCR visual text", job.results.ocr_text)

    def test_crash_after_gemini_analysis_reuses_ai_results(self):
        """6. Simulate crash after Gemini analysis succeeded but before completion marking."""
        job = self._create_base_job("item-crash-gemini")
        media_file = Path(self.temp_dir) / "media.mp4"
        media_file.write_bytes(b"media bytes")
        wav_file = Path(self.temp_dir) / "audio.wav"
        wav_file.write_bytes(b"audio wav bytes")
        media_id = self.storage.upload_media(media_file, job.item_id)
        audio_id = self.storage.upload_audio(wav_file, job.item_id)

        media_info = MediaInfo(file_name="media.mp4", mime_type="video/mp4", drive_file_id=media_id, size_bytes=24, duration_ms=10000)
        job.stages.acquisition = StageStatus.SUCCEEDED
        job.media = media_info
        job.stages.audio_extraction = StageStatus.SUCCEEDED
        job.stages.transcription = StageStatus.SUCCEEDED
        job.stages.ocr = StageStatus.SUCCEEDED
        job.stages.gemini_analysis = StageStatus.SUCCEEDED
        job.results.analysis = GeminiAnalysisResult(
            summary="Existing AI summary",
            detailed_summary="Existing detailed summary",
            main_topic="Topic A",
            category="Engineering",
            keywords=["k1"],
            tags=["tag1"],
            key_takeaways=["takeaway1"],
            important_concepts=["concept1"],
            actionable_steps=["step1"],
            model_name="gemini-3.6-flash",
            generated_at=int(time.time()),
        )

        downloader = FakeDownloader()
        extractor = FakeExtractor()
        transcriber = FakeTranscriber()
        ocr = FakeOcrExtractor()
        analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            storage=self.storage,
            config=self.config,
            downloader=downloader,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=analyzer,
            ocr_extractor=ocr,
        )

        ok = pipeline.process_job(job)
        self.assertTrue(ok)
        self.assertEqual(JobStatus.COMPLETED, job.status)
        self.assertEqual(0, analyzer.called)
        self.assertIsNotNone(job.results.analysis)
        self.assertEqual("Existing AI summary", job.results.analysis.summary)

    def test_crash_after_artifact_upload_preserves_artifacts(self):
        """7. Simulate crash where both media and audio artifacts have already been uploaded and recorded."""
        job = self._create_base_job("item-crash-artifacts")
        media_file = Path(self.temp_dir) / "media.mp4"
        media_file.write_bytes(b"media artifact")
        wav_file = Path(self.temp_dir) / "audio.wav"
        wav_file.write_bytes(b"audio artifact")
        media_id = self.storage.upload_media(media_file, job.item_id)
        audio_id = self.storage.upload_audio(wav_file, job.item_id)

        job.stages.acquisition = StageStatus.SUCCEEDED
        job.media = MediaInfo(file_name="media.mp4", mime_type="video/mp4", drive_file_id=media_id, size_bytes=14, duration_ms=5000)
        job.stages.audio_extraction = StageStatus.SUCCEEDED
        job.results.audio_drive_file_id = audio_id
        job.results.audio_size_bytes = 14
        job.artifacts["media"] = ArtifactRef(drive_file_id=media_id, mime_type="video/mp4", size_bytes=14)
        job.artifacts["audio"] = ArtifactRef(drive_file_id=audio_id, mime_type="audio/wav", size_bytes=14)

        downloader = FakeDownloader()
        extractor = FakeExtractor()
        transcriber = FakeTranscriber()
        ocr = FakeOcrExtractor()
        analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            storage=self.storage,
            config=self.config,
            downloader=downloader,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=analyzer,
            ocr_extractor=ocr,
        )

        ok = pipeline.process_job(job)
        self.assertTrue(ok)
        self.assertIn("media", job.artifacts)
        self.assertIn("audio", job.artifacts)
        self.assertEqual(media_id, job.artifacts["media"].drive_file_id)
        self.assertEqual(audio_id, job.artifacts["audio"].drive_file_id)
        self.assertEqual(0, downloader.called)
        self.assertEqual(0, extractor.called)

    def test_crash_before_completion_persistence_finalizes_safely(self):
        """8. Simulate crash right before final completion persistence.
        All stages succeeded, but JobStatus remained PROCESSING or QUEUED."""
        job = self._create_base_job("item-crash-before-completion")
        media_file = Path(self.temp_dir) / "media.mp4"
        media_file.write_bytes(b"completed media")
        wav_file = Path(self.temp_dir) / "audio.wav"
        wav_file.write_bytes(b"completed wav")
        media_id = self.storage.upload_media(media_file, job.item_id)
        audio_id = self.storage.upload_audio(wav_file, job.item_id)

        job.stages.acquisition = StageStatus.SUCCEEDED
        job.media = MediaInfo(file_name="media.mp4", mime_type="video/mp4", drive_file_id=media_id, size_bytes=15, duration_ms=5000)
        job.stages.audio_extraction = StageStatus.SUCCEEDED
        job.stages.transcription = StageStatus.SUCCEEDED
        job.stages.ocr = StageStatus.SUCCEEDED
        job.stages.gemini_analysis = StageStatus.SUCCEEDED
        job.results.transcript = "Transcript"
        job.results.ocr_text = "OCR"
        job.results.analysis = GeminiAnalysisResult(
            summary="Done",
            detailed_summary="Done detailed",
            main_topic="Topic",
            category="Category",
            keywords=[],
            tags=[],
            key_takeaways=["T1"],
            important_concepts=[],
            actionable_steps=[],
            model_name="gemini-3.6-flash",
            generated_at=1000,
        )
        job.status = JobStatus.QUEUED

        downloader = FakeDownloader()
        extractor = FakeExtractor()
        transcriber = FakeTranscriber()
        ocr = FakeOcrExtractor()
        analyzer = FakeAnalyzer()

        pipeline = CloudProcessingPipeline(
            storage=self.storage,
            config=self.config,
            downloader=downloader,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=analyzer,
            ocr_extractor=ocr,
        )

        ok = pipeline.process_job(job)
        self.assertTrue(ok)
        self.assertEqual(JobStatus.COMPLETED, job.status)
        self.assertEqual(0, downloader.called)
        self.assertEqual(0, extractor.called)
        self.assertEqual(0, transcriber.called)
        self.assertEqual(0, ocr.called)
        self.assertEqual(0, analyzer.called)

    def test_gemini_http_503_transient_failure_recovery_without_redundant_work(self):
        """9. End-to-End Recovery Flow:
        1. Initial execution: acquisition, audio extraction, transcription, OCR succeed.
        2. Gemini API fails with HTTP 503 transient error.
        3. Job transitions to WAITING_RETRY with incremented retry_count and next_retry_at backoff.
        4. Expensive artifacts remain intact in storage and manifest.
        5. Next worker run resumes job: acquisition, extraction, transcription, OCR are SKIPPED.
        6. Gemini API call succeeds.
        7. Job transitions to COMPLETED.
        8. Original URL, caption, notes, and category remain 100% unaltered."""

        job = self._create_base_job("item-gemini-503-recovery")
        self.storage.update_job(job)

        downloader = FakeDownloader()
        extractor = FakeExtractor()
        transcriber = FakeTranscriber()
        ocr = FakeOcrExtractor()

        # Phase 1: Gemini fails with HTTP 503 transient failure
        failing_analyzer = FakeAnalyzer(outcomes=[
            GeminiAnalysisOutcome(
                success=False,
                error="Gemini 503: The model is overloaded. Please try again later.",
                is_retryable=True,
            )
        ])

        pipeline_1 = CloudProcessingPipeline(
            storage=self.storage,
            config=self.config,
            downloader=downloader,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=failing_analyzer,
            ocr_extractor=ocr,
        )

        # Process first attempt
        res_1 = pipeline_1.process_job(job)
        self.assertFalse(res_1)

        # Verify state after transient failure
        self.assertEqual(JobStatus.WAITING_RETRY, job.status)
        self.assertEqual(1, job.retry_count)
        self.assertGreater(job.next_retry_at, 0)
        self.assertEqual(StageStatus.SUCCEEDED, job.stages.acquisition)
        self.assertEqual(StageStatus.SUCCEEDED, job.stages.audio_extraction)
        self.assertEqual(StageStatus.SUCCEEDED, job.stages.transcription)
        self.assertEqual(StageStatus.FAILED, job.stages.gemini_analysis)
        self.assertTrue(len(job.results.transcript) > 0)

        # Invariant checks: original user data preserved
        self.assertEqual("https://www.instagram.com/reel/CrashRecoveryTest/", job.original_url)
        self.assertEqual("Test post with valuable notes", job.caption)
        self.assertEqual("Important developer notes", job.user_notes)
        self.assertEqual("Engineering", job.category)
        self.assertEqual(["test", "recovery"], job.user_tags)

        # Verify stage call counts during run 1
        self.assertEqual(1, downloader.called)
        self.assertEqual(1, extractor.called)
        self.assertEqual(1, transcriber.called)
        self.assertEqual(1, ocr.called)
        self.assertEqual(1, failing_analyzer.called)

        # Reset call counts on mocks for run 2
        downloader.called = 0
        extractor.called = 0
        transcriber.called = 0
        ocr.called = 0

        # Phase 2: Resume worker execution after retry timer matures
        job.next_retry_at = int(time.time() * 1000) - 10_000  # Eligible for immediate pickup
        job.claimed_by = None  # Released for next worker
        job.claimed_at = None

        succeeding_analyzer = FakeAnalyzer(outcomes=[
            GeminiAnalysisOutcome(
                success=True,
                result=GeminiAnalysisResult(
                    summary="Successful AI summary on retry",
                    detailed_summary="Detailed summary after 503 recovery",
                    main_topic="Resilience",
                    category="Engineering",
                    keywords=["recovery", "backoff"],
                    tags=["resilient"],
                    key_takeaways=["No duplicated transcription"],
                    important_concepts=["Retry-safety"],
                    actionable_steps=["Persist stage states"],
                    model_name="gemini-3.6-flash",
                    generated_at=int(time.time()),
                )
            )
        ])

        pipeline_2 = CloudProcessingPipeline(
            storage=self.storage,
            config=self.config,
            downloader=downloader,
            audio_extractor=extractor,
            transcriber=transcriber,
            gemini_analyzer=succeeding_analyzer,
            ocr_extractor=ocr,
        )

        res_2 = pipeline_2.process_job(job)
        self.assertTrue(res_2)

        # Invariant verifications:
        # 1. Terminal state is COMPLETED
        self.assertEqual(JobStatus.COMPLETED, job.status)
        self.assertEqual(StageStatus.SUCCEEDED, job.stages.gemini_analysis)
        self.assertIsNotNone(job.results.analysis)
        self.assertEqual("Successful AI summary on retry", job.results.analysis.summary)

        # 2. ZERO redundant execution of previous stages
        self.assertEqual(0, downloader.called, "Media acquisition was redundantly re-run!")
        self.assertEqual(0, extractor.called, "Audio extraction was redundantly re-run!")
        self.assertEqual(0, transcriber.called, "Whisper transcription was redundantly re-run!")
        self.assertEqual(0, ocr.called, "Visual OCR was redundantly re-run!")

        # 3. Exactly 1 Gemini call during recovery
        self.assertEqual(1, succeeding_analyzer.called)

        # 4. User-authored data strictly preserved
        self.assertEqual("https://www.instagram.com/reel/CrashRecoveryTest/", job.original_url)
        self.assertEqual("Test post with valuable notes", job.caption)
        self.assertEqual("Important developer notes", job.user_notes)
        self.assertEqual("Engineering", job.category)
        self.assertEqual(["test", "recovery"], job.user_tags)


if __name__ == "__main__":
    unittest.main()
