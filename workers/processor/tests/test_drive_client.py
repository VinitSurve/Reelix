"""Unit tests for Google Drive persistent storage client."""

import unittest
from unittest.mock import MagicMock, patch

from workers.processor.src.config import WorkerConfig
from workers.processor.src.models import JobManifest, JobStatus
from workers.processor.src.storage.drive_client import GoogleDriveStorageClient


class TestDriveClient(unittest.TestCase):

    def setUp(self):
        self.config = WorkerConfig(
            gdrive_service_account_json='{"type": "service_account", "project_id": "test"}',
            gdrive_folder_id="root-folder-123",
        )
        self.client = GoogleDriveStorageClient(self.config)

    def test_ensure_subfolder_finds_existing_folder(self):
        mock_service = MagicMock()
        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "subfolder-jobs-456", "name": "jobs"}]
        }
        self.client._service = mock_service

        folder_id = self.client._ensure_subfolder("jobs")
        self.assertEqual("subfolder-jobs-456", folder_id)

    def test_ensure_subfolder_creates_when_missing(self):
        mock_service = MagicMock()
        mock_files = mock_service.files()
        # Not found initially
        mock_files.list().execute.return_value = {"files": []}
        # Created
        mock_files.create().execute.return_value = {"id": "new-subfolder-789"}
        self.client._service = mock_service

        folder_id = self.client._ensure_subfolder("audio")
        self.assertEqual("new-subfolder-789", folder_id)

    def test_list_queued_jobs_parses_manifests(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "file-1", "name": "job1.json"}]
        }

        sample_manifest = JobManifest(
            item_id="item-drive-1",
            source="URL",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=sample_manifest.to_json().encode("utf-8")):
            jobs = self.client.list_queued_jobs(limit=5)
            self.assertEqual(1, len(jobs))
            self.assertEqual("item-drive-1", jobs[0].item_id)
            self.assertEqual(JobStatus.QUEUED, jobs[0].status)


if __name__ == "__main__":
    unittest.main()
