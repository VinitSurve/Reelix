"""Categorized integration tests for Reelix: Mock Integration, Live Drive, Live Gemini, and Full E2E.

Explicit test categories:
- Mock integration tests: execute offline without external network or credentials.
- Live Google Drive tests: verify real OAuth 2.0 authentication on personal My Drive under drive.file,
  Reelix_Vault creation in 'root', upload/download checksums, and manifest operations.
- Live Gemini tests: verify real Gemini API calls strictly using gemini-3.6-flash (permitted alternative: gemini-3.8-flash).
- Full end-to-end tests: verify complete worker processing cycle against live services.

Live tests are explicitly skipped unless LIVE_INTEGRATION_TESTS=1 and external credentials are provided in the environment.
Skipped tests are reported as skipped.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
import time
import unittest
from pathlib import Path

from workers.processor.src.config import WorkerConfig
from workers.processor.src.models import (
    JobManifest,
    JobStatus,
    MediaInfo,
    StageStates,
    StageStatus,
)
from workers.processor.src.pipeline import CloudProcessingPipeline
from workers.processor.src.stages.gemini_analyzer import GeminiAnalyzer
from workers.processor.src.storage.drive_client import GoogleDriveStorageClient
from workers.processor.src.storage.mock_storage import MockStorageClient


# =====================================================================
# 1. Mock Integration Tests (Local / Offline CI)
# =====================================================================
class TestMockIntegration(unittest.TestCase):
    """Categorized mock integration tests verifying end-to-end pipeline wiring offline."""

    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="reelix_mock_int_"))
        self.storage = MockStorageClient(root_dir=self.test_dir / "storage")

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_mock_pipeline_integration_cycle(self):
        """Validates complete job processing cycle with mock storage."""
        manifest = JobManifest(
            item_id="mock-item-1",
            source="URL",
            caption="Mock pipeline integration test",
            status=JobStatus.QUEUED,
            created_at=int(time.time() * 1000),
            updated_at=int(time.time() * 1000),
            stages=StageStates(
                audio_extraction=StageStatus.SKIPPED,
                transcription=StageStatus.SKIPPED,
                gemini_analysis=StageStatus.PENDING,
            ),
        )
        self.storage.update_job(manifest)

        class StubAnalyzer:
            def analyze(self, **kwargs):
                from workers.processor.src.models import GeminiAnalysisResult
                from workers.processor.src.stages.gemini_analyzer import GeminiAnalysisOutcome

                return GeminiAnalysisOutcome(
                    success=True,
                    result=GeminiAnalysisResult(
                        summary="Test summary",
                        detailed_summary="Test detailed summary",
                        main_topic="Topic",
                        category="Engineering",
                        keywords=["test"],
                        tags=["mock"],
                        key_takeaways=["Point 1"],
                        important_concepts=["Concept 1"],
                        actionable_steps=["Step 1"],
                        model_name="gemini-3.6-flash",
                        generated_at=int(time.time() * 1000),
                    ),
                )

        config = WorkerConfig(work_dir=self.test_dir / "work", runner_id="mock-runner")
        pipeline = CloudProcessingPipeline(
            config=config,
            storage=self.storage,
            gemini_analyzer=StubAnalyzer(),
        )

        summary = pipeline.run_batch()
        self.assertEqual(1, summary.total_found)
        self.assertEqual(1, summary.succeeded)

        completed = self.storage.get_job("mock-item-1")
        self.assertIsNotNone(completed)
        self.assertEqual(JobStatus.COMPLETED, completed.status)


# =====================================================================
# 2. Live Google Drive Tests (Requires LIVE_INTEGRATION_TESTS=1 & OAuth)
# =====================================================================
class TestLiveGoogleDriveIntegration(unittest.TestCase):
    """Categorized live Google Drive integration tests against user personal account."""

    @classmethod
    def setUpClass(cls):
        cls.is_live = (
            os.getenv("LIVE_INTEGRATION_TESTS") == "1"
            and bool(os.getenv("GDRIVE_REFRESH_TOKEN"))
            and bool(os.getenv("GDRIVE_CLIENT_ID"))
            and bool(os.getenv("GDRIVE_CLIENT_SECRET"))
        )

    def setUp(self):
        if not self.is_live:
            self.skipTest(
                "Skipped: Live Google Drive tests require LIVE_INTEGRATION_TESTS=1 and "
                "GDRIVE_CLIENT_ID, GDRIVE_CLIENT_SECRET, GDRIVE_REFRESH_TOKEN in environment."
            )
        self.config = WorkerConfig.from_env()
        self.client = GoogleDriveStorageClient(self.config)
        self.test_temp_dir = Path(tempfile.mkdtemp(prefix="reelix_live_drive_"))
        self.created_file_ids: List[str] = []

    def tearDown(self):
        if hasattr(self, "client") and hasattr(self, "created_file_ids"):
            try:
                service = self.client._get_service()
                for fid in self.created_file_ids:
                    try:
                        service.files().delete(fileId=fid).execute()
                    except Exception:
                        pass
            except Exception:
                pass
        if hasattr(self, "test_temp_dir") and self.test_temp_dir.exists():
            shutil.rmtree(self.test_temp_dir, ignore_errors=True)

    def test_live_01_oauth_authentication_and_root_vault(self):
        """Operations 1, 2, 3: Verifies OAuth auth, root Reelix_Vault creation in 'root', and subfolder access."""
        vault_id = self.client._ensure_root_folder()
        self.assertIsNotNone(vault_id)
        self.assertTrue(len(vault_id) > 5)

        jobs_folder_id = self.client._ensure_subfolder("jobs")
        self.assertIsNotNone(jobs_folder_id)

    def test_live_02_media_upload_download_checksum_verification(self):
        """Operations 4, 5: Uploads small test media payload, downloads it, verifies MD5 checksum match."""
        payload_data = b"REELIX_TEST_MEDIA_PAYLOAD_SAFE_12345"
        expected_md5 = hashlib.md5(payload_data).hexdigest()

        test_file = self.test_temp_dir / "test_media.mp4"
        test_file.write_bytes(payload_data)

        # Upload audio/media
        uploaded_id = self.client.upload_audio(test_file, item_id="test-checksum-check")
        self.assertIsNotNone(uploaded_id, "Upload to Drive must return a file ID")
        self.created_file_ids.append(uploaded_id)

        # Download back and verify checksum
        download_dest = self.test_temp_dir / "downloaded.mp4"
        download_ok = self.client.download_media(uploaded_id, download_dest)
        self.assertTrue(download_ok, "Download from Drive must succeed")

        actual_md5 = hashlib.md5(download_dest.read_bytes()).hexdigest()
        self.assertEqual(expected_md5, actual_md5, "Downloaded media checksum must exactly match uploaded payload")

    def test_live_03_job_manifest_lifecycle_and_claim(self):
        """Operations 6, 7: Creates job manifest, claims it, updates it, and verifies state."""
        item_id = f"test-live-manifest-{int(time.time())}"
        manifest = JobManifest(
            item_id=item_id,
            source="URL",
            caption="Live manifest lifecycle test",
            status=JobStatus.QUEUED,
            created_at=int(time.time() * 1000),
            updated_at=int(time.time() * 1000),
        )

        # Create
        created_ok = self.client.update_job(manifest)
        self.assertTrue(created_ok, "Job manifest creation in Drive must succeed")

        # Track file ID for cleanup
        try:
            service = self.client._get_service()
            jobs_folder = self.client._ensure_subfolder("jobs")
            query = f"'{jobs_folder}' in parents and name = '{item_id}.json' and trashed = false"
            res = service.files().list(q=query, fields="files(id)").execute()
            for f in res.get("files", []):
                self.created_file_ids.append(f["id"])
        except Exception:
            pass

        # Claim
        claimed_ok = self.client.claim_job(manifest, runner_id="test-live-runner")
        self.assertTrue(claimed_ok, "Claiming queued job must succeed")

        # Verify claim
        remote = self.client.get_job(item_id)
        self.assertIsNotNone(remote)
        self.assertEqual(JobStatus.PROCESSING, remote.status)
        self.assertEqual("test-live-runner", remote.claimed_by)


# =====================================================================
# 3. Live Gemini Tests (Requires LIVE_INTEGRATION_TESTS=1 & GEMINI_API_KEY)
# =====================================================================
class TestLiveGeminiIntegration(unittest.TestCase):
    """Categorized live Google AI Studio Gemini API integration test."""

    @classmethod
    def setUpClass(cls):
        cls.is_live = (
            os.getenv("LIVE_INTEGRATION_TESTS") == "1"
            and bool(os.getenv("GEMINI_API_KEY"))
        )

    def setUp(self):
        if not self.is_live:
            self.skipTest(
                "Skipped: Live Gemini tests require LIVE_INTEGRATION_TESTS=1 and GEMINI_API_KEY in environment."
            )
        self.model = "gemini-3.6-flash"
        self.analyzer = GeminiAnalyzer(
            api_key=os.environ["GEMINI_API_KEY"],
            model=self.model,
        )

    def test_live_gemini_generation(self):
        """Operation 8: Calls live Gemini API strictly using permitted model and validates structured JSON."""
        res = self.analyzer.analyze(
            title="Clean Architecture in Android with Kotlin",
            transcript=(
                "In clean architecture, we separate domain logic into use cases and repositories. "
                "The presentation layer observes StateFlow streams from ViewModels. "
                "Data layers handle SQLite database persistence and remote network synchronization."
            ),
        )
        self.assertTrue(res.success, f"Live Gemini API call failed: {res.error}")
        self.assertIsNotNone(res.result)
        self.assertTrue(len(res.result.summary) > 0, "Gemini summary must not be empty")
        self.assertTrue(len(res.result.main_topic) > 0, "Gemini main_topic must not be empty")
        self.assertTrue(len(res.result.key_takeaways) > 0, "Gemini key_takeaways must not be empty")
        self.assertEqual(self.model, res.result.model_name)


# =====================================================================
# 4. Full End-to-End Test (Requires Both Drive OAuth & Gemini API)
# =====================================================================
class TestLiveEndToEnd(unittest.TestCase):
    """Categorized full end-to-end integration test combining live Drive and Gemini."""

    def tearDown(self):
        if hasattr(self, "test_job_id") and hasattr(self, "storage"):
            try:
                service = self.storage._get_service()
                jobs_folder = self.storage._ensure_subfolder("jobs")
                query = f"'{jobs_folder}' in parents and name = '{self.test_job_id}.json' and trashed = false"
                res = service.files().list(q=query, fields="files(id)").execute()
                for f in res.get("files", []):
                    service.files().delete(fileId=f["id"]).execute()
            except Exception:
                pass

    def test_full_pipeline_run(self):
        """Operation 9: Complete worker pipeline cycle against live external services."""
        can_run = (
            os.getenv("LIVE_INTEGRATION_TESTS") == "1"
            and bool(os.getenv("GDRIVE_REFRESH_TOKEN"))
            and bool(os.getenv("GDRIVE_CLIENT_ID"))
            and bool(os.getenv("GDRIVE_CLIENT_SECRET"))
            and bool(os.getenv("GEMINI_API_KEY"))
        )
        if not can_run:
            self.skipTest(
                "Skipped: Full end-to-end live test requires LIVE_INTEGRATION_TESTS=1, "
                "Google Drive OAuth credentials, and GEMINI_API_KEY in environment."
            )

        config = WorkerConfig.from_env()
        # Live verification strictly uses gemini-3.6-flash
        config.gemini_model = "gemini-3.6-flash"
        self.storage = GoogleDriveStorageClient(config)
        analyzer = GeminiAnalyzer(api_key=config.gemini_api_key, model="gemini-3.6-flash")
        pipeline = CloudProcessingPipeline(config=config, storage=self.storage, gemini_analyzer=analyzer)

        # Create isolated live queued job with valid content
        self.test_job_id = f"e2e-live-test-{int(time.time())}"
        job = JobManifest(
            item_id=self.test_job_id,
            source="URL",
            caption="Live end-to-end integration test of Reelix processing pipeline with Gemini analysis",
            status=JobStatus.QUEUED,
            created_at=int(time.time() * 1000),
            updated_at=int(time.time() * 1000),
            stages=StageStates(
                audio_extraction=StageStatus.SKIPPED,
                transcription=StageStatus.SKIPPED,
                gemini_analysis=StageStatus.PENDING,
            ),
        )
        self.storage.update_job(job)

        # Execute single test job directly to avoid touching other queued jobs in user vault
        success = pipeline.process_job(job)
        self.assertTrue(success)

        completed_job = self.storage.get_job(self.test_job_id)
        self.assertIsNotNone(completed_job)
        self.assertEqual(JobStatus.COMPLETED, completed_job.status)
        self.assertIsNotNone(completed_job.results.analysis)
        self.assertEqual("gemini-3.6-flash", completed_job.results.analysis.model_name)


if __name__ == "__main__":
    unittest.main()
