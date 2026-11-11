"""Unit tests for the Google Drive queue diagnostic tool."""

import json
import unittest
from unittest.mock import MagicMock

from workers.processor.scripts.diagnose_drive_queue import (
    fingerprint,
    format_id,
    mask_id,
    run_diagnostics,
)


class TestDiagnoseDriveQueue(unittest.TestCase):

    def test_mask_id_and_fingerprint_safeguards(self):
        self.assertEqual("<none>", mask_id(""))
        self.assertEqual("***", mask_id("12345"))
        self.assertEqual("1a2b...9z0w", mask_id("1a2b3c4d5e6f7g8h9z0w"))

        fp = fingerprint("my-secret-folder-id")
        self.assertEqual(16, len(fp))
        # Deterministic SHA-256
        self.assertEqual(fp, fingerprint("my-secret-folder-id"))

        fmt = format_id("1a2b3c4d5e6f7g8h9z0w")
        self.assertIn("1a2b...9z0w", fmt)
        self.assertIn("SHA256:", fmt)
        self.assertNotIn("3c4d5e6f7g8h", fmt, "Middle characters must remain redacted")

    def test_run_diagnostics_folder_matching(self):
        service = MagicMock()
        # Mock files().list() for Reelix_Vault
        service.files().list().execute.return_value = {
            "files": [{"id": "folder-123456789", "name": "Reelix_Vault", "parents": ["root"]}]
        }
        service.files().get().execute.return_value = {
            "id": "folder-123456789",
            "name": "Reelix_Vault",
            "mimeType": "application/vnd.google-apps.folder",
            "trashed": False,
            "parents": ["root"],
        }

        # Case 1: Matching Android folder
        report_match = run_diagnostics(
            service=service,
            gdrive_folder_id="folder-123456789",
            android_folder_id="folder-123456789",
        )
        self.assertTrue(report_match["folder_identity_match"])

        # Case 2: Mismatched Android folder
        report_mismatch = run_diagnostics(
            service=service,
            gdrive_folder_id="folder-123456789",
            android_folder_id="different-folder-987",
        )
        self.assertFalse(report_mismatch["folder_identity_match"])

    def test_run_diagnostics_detects_contains_query_drop(self):
        service = MagicMock()
        # Vault search
        service.files().list.return_value.execute.side_effect = [
            # 1. Global vault search
            {"files": [{"id": "root-123", "name": "Reelix_Vault"}]},
            # 2. Subfolders
            {"files": [{"id": "jobs-456", "name": "jobs"}]},
            # 3. Query A (all files in jobs): returns 2 files
            {"files": [
                {"id": "f1", "name": "uuid-1.json"},
                {"id": "f2", "name": "uuid-2.json"},
            ]},
            # 4. Query B (name contains '.json'): returns 0 files (simulating tokenizer drop)
            {"files": []},
        ]

        service.files().get.return_value.execute.return_value = {
            "id": "root-123",
            "name": "Reelix_Vault",
            "mimeType": "application/vnd.google-apps.folder",
            "trashed": False,
        }

        # Media download mock
        sample_manifest = json.dumps({"item_id": "uuid-1", "status": "QUEUED"}).encode("utf-8")
        service.files().get_media.return_value.execute.return_value = sample_manifest

        report = run_diagnostics(service=service, gdrive_folder_id="root-123")
        self.assertEqual("COMPLETED", report["status"])
        self.assertEqual(2, report["total_files_in_jobs"])
        self.assertEqual(0, report["files_with_contains_query"])
        self.assertEqual(2, report["files_dropped_by_contains_query"])
        # Verify no write/create/delete calls occurred
        self.assertFalse(service.files().create.called)
        self.assertFalse(service.files().delete.called)
        self.assertFalse(service.files().update.called)


if __name__ == "__main__":
    unittest.main()
