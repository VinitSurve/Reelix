"""Unit tests for Google Drive persistent storage client."""

import tempfile
import time
from pathlib import Path
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

    def test_list_queued_jobs_ignores_non_json_files_and_uses_safe_query(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list.return_value.execute.return_value = {
            "files": [
                {"id": "file-1", "name": "job1.json", "version": 2, "modifiedTime": "2026-11-01T12:00:00Z"},
                {"id": "file-2", "name": "readme.txt", "version": 1, "modifiedTime": "2026-11-01T12:01:00Z"},
                {"id": "file-3", "name": "preview.mp4", "version": 1, "modifiedTime": "2026-11-01T12:02:00Z"},
            ]
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
            call_kwargs = mock_files.list.call_args[1]
            self.assertIn("'jobs-folder-id' in parents and trashed = false", call_kwargs["q"])
            self.assertNotIn("name contains", call_kwargs["q"])

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

    def test_update_job_fails_if_stale_generation(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "file-1", "name": "item-stale-gen.json"}]
        }

        # Remote file was incremented to generation 2
        remote_manifest = JobManifest(
            item_id="item-stale-gen",
            source="URL",
            status=JobStatus.QUEUED,
            generation=2,
            version=1,
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            # Stale worker processing generation 1 attempts to update
            stale_job = JobManifest(
                item_id="item-stale-gen",
                source="URL",
                status=JobStatus.PROCESSING,
                claimed_by="stale-worker",
                generation=1,
                version=2,
                created_at=1000,
                updated_at=1000,
            )
            updated = self.client.update_job(stale_job)
            self.assertFalse(updated, "Stale worker with generation 1 must not overwrite generation 2 manifest")

    def test_claim_job_rejects_older_generation(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "file-1", "name": "item-gen-claim.json"}]
        }

        # Remote file has generation 2
        remote_manifest = JobManifest(
            item_id="item-gen-claim",
            source="URL",
            status=JobStatus.QUEUED,
            generation=2,
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            # Worker attempting to claim older generation 1 candidate
            older_candidate = JobManifest(
                item_id="item-gen-claim",
                source="URL",
                status=JobStatus.QUEUED,
                generation=1,
                created_at=1000,
                updated_at=1000,
            )
            claimed = self.client.claim_job(older_candidate, runner_id="worker-test")
            self.assertFalse(claimed, "Worker must reject claiming older generation than remote")

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

    def test_renew_lease_success(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        now_ms = int(time.time() * 1000)
        remote_manifest = JobManifest(
            item_id="item-renew-ok",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="worker-alpha",
            claimed_at=now_ms - 5000,
            version=1,
            created_at=now_ms - 10000,
            updated_at=now_ms - 5000,
        )

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "drive-manifest-id-1", "name": "item-renew-ok.json"}]
        }

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            res = self.client.renew_lease("item-renew-ok", "worker-alpha")
            self.assertTrue(res)
            mock_files.update.assert_called()

    def test_renew_lease_fails_if_claimed_by_other_worker(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        now_ms = int(time.time() * 1000)
        remote_manifest = JobManifest(
            item_id="item-renew-other",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="worker-other",
            claimed_at=now_ms - 5000,
            version=1,
            created_at=now_ms - 10000,
            updated_at=now_ms - 5000,
        )

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "drive-manifest-id-2", "name": "item-renew-other.json"}]
        }

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            res = self.client.renew_lease("item-renew-other", "worker-alpha")
            self.assertFalse(res)

    def test_renew_lease_fails_if_lease_expired(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        now_ms = int(time.time() * 1000)
        remote_manifest = JobManifest(
            item_id="item-renew-exp",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="worker-alpha",
            claimed_at=now_ms - 1_200_000,  # Expired > 900,000 ms
            version=1,
            created_at=now_ms - 2_000_000,
            updated_at=now_ms - 1_200_000,
        )

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "drive-manifest-id-3", "name": "item-renew-exp.json"}]
        }

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            res = self.client.renew_lease("item-renew-exp", "worker-alpha")
            self.assertFalse(res)

    def test_upload_audio_reuses_existing_artifact(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["audio"] = "audio-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "existing-audio-drive-id", "name": "item-audio-reuse.wav", "size": "1024"}]
        }

        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(b"wav content")
            local_wav = Path(f.name)

        try:
            file_id = self.client.upload_audio(local_wav, "item-audio-reuse")
            self.assertEqual("existing-audio-drive-id", file_id)
            mock_files.create.assert_not_called()
        finally:
            local_wav.unlink(missing_ok=True)

    def test_upload_media_reuses_existing_artifact(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["media"] = "media-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "existing-media-drive-id", "name": "item-media-reuse.mp4", "size": "4096"}]
        }

        with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as f:
            f.write(b"video content")
            local_mp4 = Path(f.name)

        try:
            file_id = self.client.upload_media(local_mp4, "item-media-reuse")
            self.assertEqual("existing-media-drive-id", file_id)
            mock_files.create.assert_not_called()
        finally:
            local_mp4.unlink(missing_ok=True)

    def test_upload_media_mismatched_sha256_does_not_reuse_remote(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["media"] = "media-folder-id"

        mock_files = mock_service.files()
        # Remote file has different sha256
        mock_files.list().execute.return_value = {
            "files": [{
                "id": "corrupt-media-id",
                "name": "item-mismatch.mp4",
                "size": "4096",
                "appProperties": {"reelix_sha256": "totally_different_digest_value"}
            }]
        }
        mock_files.create().execute.return_value = {"id": "new-fresh-media-id"}

        with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as f:
            f.write(b"video content")
            local_mp4 = Path(f.name)

        try:
            file_id = self.client.upload_media(local_mp4, "item-mismatch")
            # Must NOT reuse the corrupt remote file
            self.assertEqual("new-fresh-media-id", file_id)
            mock_files.create.assert_called()
        finally:
            local_mp4.unlink(missing_ok=True)

    def test_download_media_verifies_sha256_integrity(self):
        dest = Path(tempfile.mktemp(suffix=".mp4"))
        try:
            # Create a mock service and fake downloader
            with patch.object(self.client, "_get_service") as mock_get_svc, \
                 patch("googleapiclient.http.MediaIoBaseDownload") as mock_dl_cls:
                mock_dl = MagicMock()
                mock_dl.next_chunk.return_value = (None, True)
                mock_dl_cls.return_value = mock_dl

                # Simulate downloader writing content
                def write_mock_content(fh, req):
                    fh.write(b"verified payload data")
                    return mock_dl

                mock_dl_cls.side_effect = write_mock_content

                import hashlib
                expected = hashlib.sha256(b"verified payload data").hexdigest()

                ok = self.client.download_media("file-id-123", dest, expected_sha256=expected)
                self.assertTrue(ok)
                self.assertTrue(dest.exists())
        finally:
            dest.unlink(missing_ok=True)

    def test_download_media_corrupt_sha256_deletes_file(self):
        dest = Path(tempfile.mktemp(suffix=".mp4"))
        try:
            with patch.object(self.client, "_get_service") as mock_get_svc, \
                 patch("googleapiclient.http.MediaIoBaseDownload") as mock_dl_cls:
                mock_dl = MagicMock()
                mock_dl.next_chunk.return_value = (None, True)

                def write_mock_content(fh, req):
                    fh.write(b"tampered corrupt content")
                    return mock_dl

                mock_dl_cls.side_effect = write_mock_content

                ok = self.client.download_media("file-id-123", dest, expected_sha256="expected_good_hash")
                self.assertFalse(ok)
                self.assertFalse(dest.exists(), "Corrupt file should have been deleted!")
        finally:
            dest.unlink(missing_ok=True)

    def test_upload_audio_legacy_artifact_without_sha256_updates_metadata_and_reuses(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["audio"] = "audio-folder-id"

        mock_files = mock_service.files()
        # Legacy artifact: no reelix_sha256 in appProperties, but size matches local file
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(b"legacy wav payload data")
            f.flush()
            local_wav = Path(f.name)
            local_size = local_wav.stat().st_size

        try:
            mock_files.list().execute.return_value = {
                "files": [{"id": "legacy-audio-id", "name": "item-legacy.wav", "size": str(local_size), "appProperties": {}}]
            }
            mock_files.update().execute.return_value = {"id": "legacy-audio-id"}

            file_id = self.client.upload_audio(local_wav, "item-legacy")
            self.assertEqual("legacy-audio-id", file_id)
            # Must update metadata with computed SHA-256
            mock_files.update.assert_called()
            mock_files.create.assert_not_called()
        finally:
            local_wav.unlink(missing_ok=True)

    def test_upload_audio_legacy_artifact_mismatched_size_does_not_reuse(self):
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["audio"] = "audio-folder-id"

        mock_files = mock_service.files()
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(b"new fresh audio payload")
            f.flush()
            local_wav = Path(f.name)

        try:
            # Legacy file has different size (9999 vs actual)
            mock_files.list().execute.return_value = {
                "files": [{"id": "stale-legacy-id", "name": "item-legacy-stale.wav", "size": "9999", "appProperties": {}}]
            }
            mock_files.create().execute.return_value = {"id": "new-audio-id"}

            file_id = self.client.upload_audio(local_wav, "item-legacy-stale")
            self.assertEqual("new-audio-id", file_id)
            mock_files.create.assert_called()
        finally:
            local_wav.unlink(missing_ok=True)

    def test_upload_audio_generation_isolation_prevents_gen2_reusing_gen1(self):
        """Gen 2 extracting identical audio must create a separate {item_id}_gen2.wav and not reuse Gen 1."""
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["audio"] = "audio-folder-id"

        mock_files = mock_service.files()
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(b"identical wave audio content between gen 1 and gen 2")
            f.flush()
            local_wav = Path(f.name)

        try:
            # Query for gen 2 file (name = 'item-gen-iso_gen2.wav') returns empty
            mock_files.list().execute.return_value = {"files": []}
            mock_files.create().execute.return_value = {"id": "audio-gen2-id"}

            file_id = self.client.upload_audio(local_wav, "item-gen-iso", generation=2)
            self.assertEqual("audio-gen2-id", file_id)

            # Verify list query was scoped to gen2 file name
            list_call = mock_files.list.call_args[1]
            self.assertIn("name = 'item-gen-iso_gen2.wav'", list_call["q"])

            # Verify create file metadata contained generation 2
            create_call = mock_files.create.call_args[1]
            self.assertEqual("item-gen-iso_gen2.wav", create_call["body"]["name"])
            self.assertEqual("2", create_call["body"]["appProperties"]["reelix_generation"])
        finally:
            local_wav.unlink(missing_ok=True)

    def test_upload_audio_gen2_retry_reuses_gen2_artifact(self):
        """A retry within Gen 2 reuses the already-uploaded Gen 2 audio artifact if hash matches."""
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["audio"] = "audio-folder-id"

        mock_files = mock_service.files()
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(b"retry wave audio content")
            f.flush()
            local_wav = Path(f.name)
            local_size = local_wav.stat().st_size

        from workers.processor.src.models import compute_file_sha256
        sha256 = compute_file_sha256(local_wav)

        try:
            # Query returns existing Gen 2 file
            mock_files.list().execute.return_value = {
                "files": [{
                    "id": "audio-gen2-existing-id",
                    "name": "item-gen2-retry_gen2.wav",
                    "size": str(local_size),
                    "appProperties": {
                        "reelix_item_id": "item-gen2-retry",
                        "reelix_sha256": sha256,
                        "reelix_generation": "2",
                    }
                }]
            }

            file_id = self.client.upload_audio(local_wav, "item-gen2-retry", generation=2)
            self.assertEqual("audio-gen2-existing-id", file_id)
            mock_files.create.assert_not_called()
        finally:
            local_wav.unlink(missing_ok=True)

    def test_update_job_rejects_with_412_precondition_failed_stale_write(self):
        """Worker with old ETag is rejected with HTTP 412 by Google Drive when remote manifest was updated."""
        import httplib2
        from googleapiclient.errors import HttpError

        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "manifest-file-id", "name": "item-stale.json", "version": 2, "etag": "etag-v2-after-android"}]
        }

        # Simulated remote manifest at generation 1
        remote_manifest = JobManifest(
            item_id="item-stale",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="runner-1",
            generation=1,
            version=1,
            etag="etag-v2-after-android",
            created_at=1000,
            updated_at=1000,
        )

        # Worker still has older etag-v1
        worker_job = JobManifest(
            item_id="item-stale",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="runner-1",
            generation=1,
            version=1,
            etag="etag-v1-old",
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            # Simulate Drive throwing 412 when If-Match header does not match server etag
            mock_update_req = MagicMock()
            mock_update_req.headers = {}
            resp = httplib2.Response({"status": 412, "reason": "Precondition Failed"})
            http_err = HttpError(resp, b"Precondition Failed: ConditionNotMet")
            mock_update_req.execute.side_effect = http_err
            mock_files.update.return_value = mock_update_req

            updated = self.client.update_job(worker_job)
            self.assertFalse(updated, "update_job must return False when Google Drive returns HTTP 412 Precondition Failed")
            # Verify If-Match header was passed with worker's expected etag
            self.assertEqual("etag-v1-old", mock_update_req.headers.get("If-Match"))

    def test_update_job_rejects_stale_worker_with_older_generation(self):
        """Worker running generation 1 is rejected if remote manifest has been bumped to generation 2."""
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "manifest-file-id", "name": "item-gen-stale.json", "version": 1, "etag": "etag-gen2"}]
        }

        remote_manifest = JobManifest(
            item_id="item-gen-stale",
            source="URL",
            status=JobStatus.QUEUED,
            generation=2,
            version=1,
            created_at=2000,
            updated_at=2000,
        )

        worker_job = JobManifest(
            item_id="item-gen-stale",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="runner-1",
            generation=1,
            version=1,
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            updated = self.client.update_job(worker_job)
            self.assertFalse(updated, "Stale worker with older generation must be rejected!")
            mock_files.update.assert_not_called()

    def test_claim_job_rejects_with_412_on_concurrent_modification(self):
        """claim_job passes If-Match and aborts safely if Google Drive rejects with 412."""
        import httplib2
        from googleapiclient.errors import HttpError

        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "claim-file-id", "name": "item-claim.json", "version": 1, "etag": "etag-claim-1"}]
        }

        remote_manifest = JobManifest(
            item_id="item-claim",
            source="URL",
            status=JobStatus.QUEUED,
            generation=1,
            version=1,
            created_at=1000,
            updated_at=1000,
        )

        worker_job = JobManifest(
            item_id="item-claim",
            source="URL",
            status=JobStatus.QUEUED,
            generation=1,
            version=1,
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            resp = httplib2.Response({"status": 412, "reason": "Precondition Failed"})
            mock_files.update.return_value.execute.side_effect = HttpError(resp, b"Precondition Failed")

            claimed = self.client.claim_job(worker_job, runner_id="test-runner")
            self.assertFalse(claimed)

    def test_renew_lease_rejects_when_generation_mismatches(self):
        """renew_lease aborts immediately if remote generation does not match worker generation."""
        mock_service = MagicMock()
        self.client._service = mock_service
        self.client._subfolders["jobs"] = "jobs-folder-id"

        mock_files = mock_service.files()
        mock_files.list().execute.return_value = {
            "files": [{"id": "renew-file-id", "name": "item-renew.json", "version": 1, "etag": "etag-renew"}]
        }

        remote_manifest = JobManifest(
            item_id="item-renew",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="runner-1",
            generation=2,
            claimed_at=int(time.time() * 1000),
            created_at=1000,
            updated_at=1000,
        )

        worker_job = JobManifest(
            item_id="item-renew",
            source="URL",
            status=JobStatus.PROCESSING,
            claimed_by="runner-1",
            generation=1,
            created_at=1000,
            updated_at=1000,
        )

        with patch.object(self.client, "_download_bytes", return_value=remote_manifest.to_json().encode("utf-8")):
            renewed = self.client.renew_lease("item-renew", "runner-1", job=worker_job)
            self.assertFalse(renewed, "renew_lease must return False when generation has diverged!")


if __name__ == "__main__":
    unittest.main()
