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
            model_name="gemini-3.8-flash",
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


if __name__ == "__main__":
    unittest.main()

