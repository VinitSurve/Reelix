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
        mock_files.list().execute.return_value = {"files": []}
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
            "files": [{"id": "file-1", "name": "job1.json", "version": 2, "modifiedTime": "2026-11-01T12:00:00Z"}]
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

    @patch("google.oauth2.credentials.Credentials")
    @patch("googleapiclient.discovery.build")
    def test_oauth_credentials_initialization(self, mock_build, mock_creds_cls):
        oauth_config = WorkerConfig(
            gdrive_client_id="client-id-xyz",
            gdrive_client_secret="client-secret-123",
            gdrive_refresh_token="refresh-token-abc",
            gdrive_folder_id="root-folder-123",
        )
        client = GoogleDriveStorageClient(oauth_config)
        mock_creds_instance = MagicMock()
        mock_creds_cls.return_value = mock_creds_instance

        service = client._get_service()
        self.assertIsNotNone(service)
        mock_creds_instance.refresh.assert_called_once()
        mock_build.assert_called_once()

    def test_missing_credentials_raises_descriptive_error(self):
        empty_config = WorkerConfig(gdrive_folder_id="root-folder-123")
        client = GoogleDriveStorageClient(empty_config)
        with self.assertRaises(ValueError) as ctx:
            client._get_service()
        self.assertIn("GDRIVE_CLIENT_ID", str(ctx.exception))
        self.assertIn("GDRIVE_REFRESH_TOKEN", str(ctx.exception))

    def test_claim_job_active_lease_rejected(self):
        import time
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "file-1", "name": "item-active.json"}]
        }

        # Remote file is currently being processed by worker-alpha with an active lease (5 min ago)
        active_manifest = JobManifest(
            item_id="item-active",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="worker-alpha",
            claimed_at=int(time.time() * 1000) - 300_000,  # 5 minutes ago (< 15m)
            lease_timeout_ms=900_000,
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=active_manifest.to_json().encode("utf-8")):
            candidate = JobManifest(item_id="item-active", source="URL", created_at=1000, updated_at=1000)
            claimed = self.client.claim_job(candidate, runner_id="worker-beta")
            self.assertFalse(claimed, "Active lease must not be claimed by another worker")

    def test_claim_job_stale_lease_recovered(self):
        import time
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "file-1", "name": "item-stale.json"}]
        }

        # Remote file was claimed 25 minutes ago (> 15m lease expired)
        stale_manifest = JobManifest(
            item_id="item-stale",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="worker-crashed",
            claimed_at=int(time.time() * 1000) - 1_500_000,  # 25 minutes ago
            lease_timeout_ms=900_000,
            created_at=1000,
            updated_at=1000,
        )

        # First download returns stale manifest; second download (verification) returns candidate claimed by worker-beta
        verified_manifest = JobManifest(
            item_id="item-stale",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="worker-beta",
            claimed_at=int(time.time() * 1000),
            lease_timeout_ms=900_000,
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(
            self.client,
            "_download_bytes",
            side_effect=[
                stale_manifest.to_json().encode("utf-8"),
                verified_manifest.to_json().encode("utf-8"),
            ],
        ):
            candidate = JobManifest(item_id="item-stale", source="URL", created_at=1000, updated_at=1000)
            claimed = self.client.claim_job(candidate, runner_id="worker-beta")
            self.assertTrue(claimed, "Stale lease must be reclaimed after timeout")

    def test_claim_job_read_after_write_collision_aborted(self):
        import time
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "file-1", "name": "item-collision.json"}]
        }

        queued_manifest = JobManifest(
            item_id="item-collision",
            source="URL",
            status=JobStatus.QUEUED,
            created_at=1000,
            updated_at=1000,
        )

        # Remote file on verification was overwritten by competing worker-gamma
        collision_manifest = JobManifest(
            item_id="item-collision",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="worker-gamma",
            claimed_at=int(time.time() * 1000),
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(
            self.client,
            "_download_bytes",
            side_effect=[
                queued_manifest.to_json().encode("utf-8"),
                collision_manifest.to_json().encode("utf-8"),
            ],
        ):
            candidate = JobManifest(item_id="item-collision", source="URL", created_at=1000, updated_at=1000)
            claimed = self.client.claim_job(candidate, runner_id="worker-beta")
            self.assertFalse(claimed, "Worker must abort when post-write verification detects another runner won")

    def test_update_job_fails_if_lease_stolen(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "file-1", "name": "item-stolen.json"}]
        }

        # Remote file is now claimed by worker-gamma
        remote_manifest = JobManifest(
            item_id="item-stolen",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="worker-gamma",
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            # worker-beta attempts update
            my_job = JobManifest(
                item_id="item-stolen",
                source="URL",
                status=JobStatus.PROCESSING,
                claimed_by="worker-beta",
                created_at=1000,
                updated_at=1000,
            )
            updated = self.client.update_job(my_job)
            self.assertFalse(updated, "Update must fail if remote lease was reclaimed by another worker")

    def test_update_job_fails_if_already_settled(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "file-1", "name": "item-settled.json"}]
        }

        # Remote file was already marked COMPLETED
        remote_manifest = JobManifest(
            item_id="item-settled",
            source="URL",
            status=JobStatus.COMPLETED,
            claimed_by="worker-fast",
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            my_job = JobManifest(
                item_id="item-settled",
                source="URL",
                status=JobStatus.PROCESSING,
                claimed_by="worker-slow",
                created_at=1000,
                updated_at=1000,
            )
            updated = self.client.update_job(my_job)
            self.assertFalse(updated, "Stale worker must not overwrite an already settled job")

    def test_update_job_fails_if_remote_version_ahead(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "file-1", "name": "item-version.json"}]
        }

        # Remote file has version 3
        remote_manifest = JobManifest(
            item_id="item-version",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="worker-beta",
            version=3,
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            my_job = JobManifest(
                item_id="item-version",
                source="URL",
                status=JobStatus.PROCESSING,
                claimed_by="worker-beta",
                version=1,  # Stale local version
                created_at=1000,
                updated_at=1000,
            )
            updated = self.client.update_job(my_job)
            self.assertFalse(updated, "Stale worker version must not overwrite newer remote version")

    def test_root_vault_auto_discovery_and_creation(self):
        empty_config = WorkerConfig(
            gdrive_service_account_json='{"type": "service_account"}',
            gdrive_folder_id=None,
        )
        client = GoogleDriveStorageClient(empty_config)
        mock_service = MagicMock()
        client._service = mock_service

        # Discovery case: folder found
        mock_service.files().list().execute.return_value = {
            "files": [{"id": "found-vault-id", "name": "Reelix_Vault"}]
        }
        vault_id = client._ensure_root_folder()
        self.assertEqual("found-vault-id", vault_id)

        # Creation case: folder missing
        client.folder_id = None
        mock_service.files().list().execute.return_value = {"files": []}
        mock_service.files().create().execute.return_value = {"id": "created-vault-id"}
        vault_id_created = client._ensure_root_folder()
        self.assertEqual("created-vault-id", vault_id_created)


if __name__ == "__main__":
    unittest.main()
