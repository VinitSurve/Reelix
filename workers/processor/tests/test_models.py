"""Unit tests for JobManifest and related data models."""

import json
import unittest

from workers.processor.src.models import (
    GeminiAnalysisResult,
    JobManifest,
    JobResults,
    JobStatus,
    MediaInfo,
    StageStates,
    StageStatus,
)


class TestModels(unittest.TestCase):

    def test_job_manifest_json_roundtrip(self):
        analysis = GeminiAnalysisResult(
            summary="Quick summary",
            detailed_summary="In-depth breakdown of concepts",
            main_topic="Jetpack Compose",
            category="Android",
            keywords=["compose", "state"],
            tags=["ui", "mobile"],
            key_takeaways=["Hoist state"],
            important_concepts=["Unidirectional data flow"],
            actionable_steps=["Pass events upwards"],
            model_name="gemini-3.6-flash",
            generated_at=1730450000000,
        )

        manifest = JobManifest(
            item_id="item-test-123",
            source="MEDIA_SHARE",
            original_url="https://www.instagram.com/reel/Test1234/",
            status=JobStatus.COMPLETED,
            claimed_by="gha-worker-1",
            claimed_at=1730450000000,
            created_at=1730449000000,
            updated_at=1730450000000,
            retry_count=1,
            max_retries=3,
            media=MediaInfo(
                file_name="sample_reel.mp4",
                mime_type="video/mp4",
                drive_file_id="drive-file-abc",
                size_bytes=1048576,
                duration_ms=15000,
            ),
            stages=StageStates(
                audio_extraction=StageStatus.SUCCEEDED,
                transcription=StageStatus.SUCCEEDED,
                gemini_analysis=StageStatus.SUCCEEDED,
            ),
            results=JobResults(
                audio_drive_file_id="audio-drive-def",
                audio_size_bytes=480000,
                transcript="Welcome to this video on Jetpack Compose.",
                transcript_model="base",
                analysis=analysis,
            ),
        )

        json_str = manifest.to_json()
        restored = JobManifest.from_json(json_str)

        self.assertEqual(manifest.item_id, restored.item_id)
        self.assertEqual(manifest.status, restored.status)
        self.assertEqual(JobStatus.COMPLETED, restored.status)
        self.assertEqual(StageStatus.PENDING, restored.stages.acquisition)
        self.assertEqual(StageStatus.SUCCEEDED, restored.stages.audio_extraction)
        self.assertEqual(StageStatus.SUCCEEDED, restored.stages.transcription)
        self.assertEqual(StageStatus.SUCCEEDED, restored.stages.gemini_analysis)

        self.assertIsNotNone(restored.media)
        self.assertEqual("sample_reel.mp4", restored.media.file_name)
        self.assertEqual("drive-file-abc", restored.media.drive_file_id)

        self.assertIsNotNone(restored.results.analysis)
        self.assertEqual("Jetpack Compose", restored.results.analysis.main_topic)
        self.assertEqual(["compose", "state"], restored.results.analysis.keywords)
        self.assertEqual(["Hoist state"], restored.results.analysis.key_takeaways)
        self.assertEqual(["Unidirectional data flow"], restored.results.analysis.important_concepts)
        self.assertEqual(["Pass events upwards"], restored.results.analysis.actionable_steps)

    def test_backward_compatibility_old_manifest_without_acquisition_field(self):
        # Legacy manifest JSON without "acquisition" key in stages
        old_json = """
        {
          "version": 1,
          "item_id": "legacy-item-999",
          "source": "MEDIA_SHARE",
          "status": "QUEUED",
          "created_at": 1730000000000,
          "updated_at": 1730000000000,
          "media": {
            "file_name": "legacy_reel.mp4",
            "mime_type": "video/mp4",
            "drive_file_id": "legacy_drive_file_id",
            "size_bytes": 500000
          },
          "stages": {
            "audio_extraction": "PENDING",
            "transcription": "PENDING",
            "gemini_analysis": "PENDING"
          },
          "results": {}
        }
        """
        restored = JobManifest.from_json(old_json)
        self.assertEqual(restored.item_id, "legacy-item-999")
        # Since media was already present, acquisition defaults to SKIPPED
        self.assertEqual(restored.stages.acquisition, StageStatus.SKIPPED)
        self.assertEqual(restored.stages.audio_extraction, StageStatus.PENDING)
        self.assertIsNotNone(restored.media)
        self.assertEqual(restored.media.file_name, "legacy_reel.mp4")

    def test_backward_compatibility_old_url_manifest_without_acquisition_field(self):
        # Legacy URL-only manifest JSON without media and without acquisition stage
        old_url_json = """
        {
          "version": 1,
          "item_id": "legacy-url-888",
          "source": "SHARE",
          "original_url": "https://www.instagram.com/reel/LegacyUrl123/",
          "status": "QUEUED",
          "created_at": 1730000000000,
          "updated_at": 1730000000000,
          "stages": {
            "audio_extraction": "SKIPPED",
            "transcription": "SKIPPED",
            "gemini_analysis": "PENDING"
          },
          "results": {}
        }
        """
        restored = JobManifest.from_json(old_url_json)
        self.assertEqual(restored.item_id, "legacy-url-888")
        # Since media is None and acquisition wasn't present, acquisition defaults to PENDING
        self.assertEqual(restored.stages.acquisition, StageStatus.PENDING)


if __name__ == "__main__":
    unittest.main()

