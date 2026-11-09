"""Unit tests for verify_cross_client.py and android_probe_helper.py safeguards."""

from __future__ import annotations

import io
import json
import os
import unittest
from unittest.mock import MagicMock, call, patch

from workers.processor.scripts.android_probe_helper import (
    get_access_token,
    validate_drive_id,
)
from workers.processor.scripts.verify_cross_client import (
    SAFE_FOLDER_PREFIX,
    cleanup_validated_test_targets,
    list_all_children,
    run_cross_client_verification,
    validate_test_targets,
    verify_folder_is_in_root,
)


class TestVerifyCrossClientSafeguards(unittest.TestCase):
    """Verifies all safety checks, target validation, root resolution, and cleanup isolation."""

    def setUp(self):
        self.mock_service = MagicMock()
        self.valid_folder_id = "test_folder_12345"
        self.valid_file_id = "test_file_67890"
        self.valid_folder_name = f"{SAFE_FOLDER_PREFIX}20261102_abcd"
        self.production_vault_id = "production_vault_99999"
        self.real_root_id = "0Areal_drive_root_id_98765"
        self.valid_test_id = "20261102_abcd"
        self._setup_valid_mocks()

    def _setup_valid_mocks(self):
        """Configures mock_service to return valid folder, file, root ID, and children."""
        def files_get(fileId, fields=None):
            req = MagicMock()
            if fileId == "root":
                req.execute.return_value = {"id": self.real_root_id}
            elif fileId == self.valid_folder_id:
                req.execute.return_value = {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": [self.real_root_id],
                    "trashed": False,
                    "properties": {
                        "reelix_type": "cross_client_test",
                        "reelix_test_id": self.valid_test_id,
                    },
                }
            elif fileId == self.valid_file_id:
                req.execute.return_value = {
                    "id": self.valid_file_id,
                    "name": "manifest_probe.json",
                    "mimeType": "application/json",
                    "parents": [self.valid_folder_id],
                    "trashed": False,
                    "properties": {
                        "reelix_test_id": self.valid_test_id,
                    },
                }
            else:
                req.execute.side_effect = Exception(f"Unknown fileId {fileId}")
            return req

        def files_list(q=None, fields=None, pageToken=None, pageSize=None):
            req = MagicMock()
            if q and "'root' in parents" in q:
                req.execute.return_value = {
                    "files": [
                        {
                            "id": self.valid_folder_id,
                            "name": self.valid_folder_name,
                            "mimeType": "application/vnd.google-apps.folder",
                            "parents": [self.real_root_id],
                        }
                    ],
                    "nextPageToken": None,
                }
            else:
                req.execute.return_value = {
                    "files": [
                        {
                            "id": self.valid_file_id,
                            "name": "manifest_probe.json",
                            "mimeType": "application/json",
                            "parents": [self.valid_folder_id],
                        }
                    ],
                    "nextPageToken": None,
                }
            return req

        self.mock_service.files().get.side_effect = files_get
        self.mock_service.files().list.side_effect = files_list

    def test_validation_never_calls_files_get_on_root_and_uses_no_404_parsing(self):
        """validate_test_targets must NEVER call files().get(fileId='root') and must not parse 404 errors."""
        self._setup_valid_mocks()
        orig_get = self.mock_service.files().get.side_effect

        def guarded_files_get(fileId, fields=None):
            if fileId == "root":
                self.fail("SECURITY VIOLATION: files().get(fileId='root') must NEVER be called!")
            return orig_get(fileId, fields=fields)

        self.mock_service.files().get.side_effect = guarded_files_get

        folder, file_obj = validate_test_targets(
            self.mock_service,
            self.valid_folder_id,
            self.valid_file_id,
            self.production_vault_id,
        )
        self.assertEqual(self.valid_folder_id, folder["id"])
        self.assertEqual(self.valid_file_id, file_obj["id"])

    def test_folder_with_missing_parents_metadata_rejected(self):
        """Folder with missing or empty 'parents' list must be rejected."""
        self._setup_valid_mocks()

        def files_get_no_parents(fileId, fields=None):
            req = MagicMock()
            if fileId == self.valid_folder_id:
                req.execute.return_value = {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": [],
                    "trashed": False,
                    "properties": {
                        "reelix_type": "cross_client_test",
                        "reelix_test_id": self.valid_test_id,
                    },
                }
            return req

        self.mock_service.files().get.side_effect = files_get_no_parents

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("has no parents listed in Drive", str(ctx.exception))

    def test_folder_with_empty_parent_string_rejected(self):
        """Folder with malformed parent string ('') must be rejected."""
        self._setup_valid_mocks()

        def files_get_empty_parent(fileId, fields=None):
            req = MagicMock()
            if fileId == self.valid_folder_id:
                req.execute.return_value = {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": [""],
                    "trashed": False,
                    "properties": {
                        "reelix_type": "cross_client_test",
                        "reelix_test_id": self.valid_test_id,
                    },
                }
            return req

        self.mock_service.files().get.side_effect = files_get_empty_parent

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("not located directly in Drive root", str(ctx.exception))

    def test_verify_folder_is_in_root_paginates_and_finds_folder(self):
        """verify_folder_is_in_root traverses pages of root query and identifies test folder."""
        call_count = [0]

        def files_list(q=None, fields=None, pageToken=None, pageSize=None):
            req = MagicMock()
            call_count[0] += 1
            if pageToken is None:
                # Page 1: contains other folder in root
                req.execute.return_value = {
                    "files": [
                        {
                            "id": "other_folder_id_777",
                            "name": "Other_Folder",
                            "mimeType": "application/vnd.google-apps.folder",
                            "parents": [self.real_root_id],
                        }
                    ],
                    "nextPageToken": "token_page_2",
                }
            else:
                # Page 2: contains valid_folder_id
                req.execute.return_value = {
                    "files": [
                        {
                            "id": self.valid_folder_id,
                            "name": self.valid_folder_name,
                            "mimeType": "application/vnd.google-apps.folder",
                            "parents": [self.real_root_id],
                        }
                    ],
                    "nextPageToken": None,
                }
            return req

        self.mock_service.files().list.side_effect = files_list
        parents = verify_folder_is_in_root(self.mock_service, self.valid_folder_id, self.valid_folder_name)
        self.assertEqual([self.real_root_id], parents)
        self.assertEqual(2, call_count[0])

    def test_verify_folder_is_in_root_aborts_when_not_returned_by_root_in_parents(self):
        """When Drive 'root' in parents query does not return folder, raises ValueError."""
        self.mock_service.files().list.side_effect = None
        req = MagicMock()
        req.execute.return_value = {"files": [], "nextPageToken": None}
        self.mock_service.files().list.return_value = req

        with self.assertRaises(ValueError) as ctx:
            verify_folder_is_in_root(self.mock_service, self.valid_folder_id, self.valid_folder_name)
        self.assertIn("not located directly in Drive root", str(ctx.exception))

    def test_verify_folder_is_in_root_empty_parents_rejected(self):
        """When Drive query returns folder with empty parents list, raises ValueError."""
        self._setup_valid_mocks()
        req = MagicMock()
        req.execute.return_value = {
            "files": [
                {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": [],
                }
            ],
            "nextPageToken": None,
        }
        self.mock_service.files().list.side_effect = None
        self.mock_service.files().list.return_value = req

        with self.assertRaises(ValueError) as ctx:
            verify_folder_is_in_root(self.mock_service, self.valid_folder_id, self.valid_folder_name)
        self.assertIn("returned empty parents list", str(ctx.exception))

    def test_verify_folder_is_in_root_literal_root_rejected(self):
        """When Drive query returns folder with literal 'root' parent ID, raises ValueError."""
        self._setup_valid_mocks()
        req = MagicMock()
        req.execute.return_value = {
            "files": [
                {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": ["root"],
                }
            ],
            "nextPageToken": None,
        }
        self.mock_service.files().list.side_effect = None
        self.mock_service.files().list.return_value = req

        with self.assertRaises(ValueError) as ctx:
            verify_folder_is_in_root(self.mock_service, self.valid_folder_id, self.valid_folder_name)
        self.assertIn("invalid root parent ID 'root'", str(ctx.exception))

    def test_root_folder_validation_succeeds_with_resolved_real_root_id(self):
        """Verifies that folder parentage is compared against the resolved real root ID and accepted."""
        self._setup_valid_mocks()
        folder, file_obj = validate_test_targets(
            self.mock_service,
            self.valid_folder_id,
            self.valid_file_id,
            self.production_vault_id,
        )
        self.assertEqual(self.valid_folder_id, folder["id"])
        self.assertEqual(self.valid_file_id, file_obj["id"])

    def test_root_query_failure_aborts_validation(self):
        """When Drive query API fails with HTTP 500, validate_test_targets raises HttpError and aborts."""
        from googleapiclient.errors import HttpError

        self._setup_valid_mocks()
        resp = MagicMock()
        resp.status = 500
        http_err = HttpError(resp, b"Root query failed")

        def files_list_error(q=None, fields=None, pageToken=None, pageSize=None):
            req = MagicMock()
            req.execute.side_effect = http_err
            return req

        self.mock_service.files().list.side_effect = files_list_error

        with self.assertRaises(HttpError):
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )

    def test_root_matching_production_vault_rejected(self):
        """If resolved actual root ID matches production vault ID, validation must reject."""
        self._setup_valid_mocks()

        def files_list_vault_root(q=None, fields=None, pageToken=None, pageSize=None):
            req = MagicMock()
            if q and "'root' in parents" in q:
                req.execute.return_value = {
                    "files": [
                        {
                            "id": self.valid_folder_id,
                            "name": self.valid_folder_name,
                            "mimeType": "application/vnd.google-apps.folder",
                            "parents": [self.production_vault_id],
                        }
                    ],
                    "nextPageToken": None,
                }
            else:
                req.execute.return_value = {
                    "files": [
                        {
                            "id": self.valid_file_id,
                            "name": "manifest_probe.json",
                            "mimeType": "application/json",
                            "parents": [self.valid_folder_id],
                        }
                    ],
                    "nextPageToken": None,
                }
            return req

        self.mock_service.files().list.side_effect = files_list_vault_root

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertTrue(
            "nested inside production vault" in str(ctx.exception)
            or "matches production vault ID" in str(ctx.exception)
            or "not located directly in Drive root" in str(ctx.exception)
        )

    def test_root_query_item_not_folder_rejected(self):
        """If the item returned by root query is not a folder, validation must reject."""
        self._setup_valid_mocks()

        def files_list_not_folder(q=None, fields=None, pageToken=None, pageSize=None):
            req = MagicMock()
            if q and "'root' in parents" in q:
                req.execute.return_value = {
                    "files": [
                        {
                            "id": self.valid_folder_id,
                            "name": self.valid_folder_name,
                            "mimeType": "application/json",
                            "parents": [self.real_root_id],
                        }
                    ],
                    "nextPageToken": None,
                }
            return req

        self.mock_service.files().list.side_effect = files_list_not_folder

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("returned by root query is not a folder", str(ctx.exception))

    def test_folder_with_literal_root_parent_but_unmatched_actual_root_id_rejected(self):
        """A folder with literal 'root' parent string must be REJECTED when actual root ID is a real alphanumeric ID."""
        self._setup_valid_mocks()

        def files_get_with_literal_root(fileId, fields=None):
            req = MagicMock()
            if fileId == "root":
                req.execute.return_value = {"id": self.real_root_id}  # Resolved real root ID
            elif fileId == self.valid_folder_id:
                req.execute.return_value = {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": ["root"],  # Literal string 'root', NOT matching self.real_root_id
                    "trashed": False,
                    "properties": {
                        "reelix_type": "cross_client_test",
                        "reelix_test_id": self.valid_test_id,
                    },
                }
            return req

        self.mock_service.files().get.side_effect = files_get_with_literal_root

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("is not located directly in Drive root", str(ctx.exception))

    def test_invalid_folder_id_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(self.mock_service, "", self.valid_file_id, self.production_vault_id)
        self.assertIn("Target folder_id must not be empty", str(ctx.exception))

    def test_invalid_file_id_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(self.mock_service, self.valid_folder_id, "  ", self.production_vault_id)
        self.assertIn("Target file_id must not be empty", str(ctx.exception))

    def test_production_folder_id_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.production_vault_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("matches production GDRIVE_FOLDER_ID", str(ctx.exception))

    def test_production_file_id_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.production_vault_id,
                self.production_vault_id,
            )
        self.assertIn("matches production GDRIVE_FOLDER_ID", str(ctx.exception))

    def test_wrong_folder_name_rejected(self):
        self._setup_valid_mocks()
        self.mock_service.files().get.side_effect = None
        req = MagicMock()
        req.execute.return_value = {
            "id": self.valid_folder_id,
            "name": "NotATestFolder_123",
            "mimeType": "application/vnd.google-apps.folder",
            "parents": [self.real_root_id],
            "trashed": False,
        }
        self.mock_service.files().get.return_value = req

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("does not start with required prefix", str(ctx.exception))

    def test_nested_folder_rejected_when_not_under_root(self):
        self._setup_valid_mocks()

        def files_get(fileId, fields=None):
            req = MagicMock()
            if fileId == "root":
                req.execute.return_value = {"id": self.real_root_id}
            elif fileId == self.valid_folder_id:
                req.execute.return_value = {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": ["some_unrelated_subfolder_id"],
                    "trashed": False,
                    "properties": {
                        "reelix_type": "cross_client_test",
                        "reelix_test_id": self.valid_test_id,
                    },
                }
            return req

        def files_list(q=None, fields=None, pageToken=None, pageSize=None):
            req = MagicMock()
            if q and "'root' in parents" in q:
                req.execute.return_value = {"files": [], "nextPageToken": None}
            else:
                req.execute.return_value = {"files": [], "nextPageToken": None}
            return req

        self.mock_service.files().get.side_effect = files_get
        self.mock_service.files().list.side_effect = files_list

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("is not located directly in Drive root", str(ctx.exception))

    def test_nested_folder_rejected_when_under_production_vault(self):
        def files_get(fileId, fields=None):
            req = MagicMock()
            if fileId == "root":
                req.execute.return_value = {"id": self.real_root_id}
            elif fileId == self.valid_folder_id:
                req.execute.return_value = {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": [self.real_root_id, self.production_vault_id],
                    "trashed": False,
                    "properties": {
                        "reelix_type": "cross_client_test",
                        "reelix_test_id": self.valid_test_id,
                    },
                }
            return req

        self.mock_service.files().get.side_effect = files_get

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("nested inside production vault", str(ctx.exception))

    def test_file_parent_mismatch_rejected(self):
        def files_get(fileId, fields=None):
            req = MagicMock()
            if fileId == "root":
                req.execute.return_value = {"id": self.real_root_id}
            elif fileId == self.valid_folder_id:
                req.execute.return_value = {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": [self.real_root_id],
                    "trashed": False,
                    "properties": {
                        "reelix_type": "cross_client_test",
                        "reelix_test_id": self.valid_test_id,
                    },
                }
            elif fileId == self.valid_file_id:
                req.execute.return_value = {
                    "id": self.valid_file_id,
                    "name": "manifest_probe.json",
                    "mimeType": "application/json",
                    "parents": ["different_folder_id"],
                    "trashed": False,
                    "properties": {
                        "reelix_test_id": self.valid_test_id,
                    },
                }
            return req

        self.mock_service.files().get.side_effect = files_get

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("does not belong to supplied folder_id", str(ctx.exception))

    def test_pagination_detects_unexpected_file_on_later_page_during_validation(self):
        """Verifies that pagination follows nextPageToken and rejects unexpected items on subsequent pages."""
        self._setup_valid_mocks()

        page1 = {
            "files": [{"id": self.valid_file_id, "name": "manifest_probe.json", "mimeType": "application/json"}],
            "nextPageToken": "page_2_token",
        }
        page2 = {
            "files": [{"id": "unexpected_file_page2", "name": "secret.pdf", "mimeType": "application/pdf"}],
            "nextPageToken": None,
        }

        def paginated_list(q=None, fields=None, pageToken=None, pageSize=None):
            req = MagicMock()
            if q and "'root' in parents" in q:
                req.execute.return_value = {
                    "files": [
                        {
                            "id": self.valid_folder_id,
                            "name": self.valid_folder_name,
                            "mimeType": "application/vnd.google-apps.folder",
                            "parents": [self.real_root_id],
                        }
                    ],
                    "nextPageToken": None,
                }
                return req
            if pageToken == "page_2_token":
                req.execute.return_value = page2
            else:
                req.execute.return_value = page1
            return req

        self.mock_service.files().list.side_effect = paginated_list

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("Test folder must contain ONLY", str(ctx.exception))
        self.assertIn("Found 2 item(s) across all pages", str(ctx.exception))

    def test_pagination_detects_unexpected_file_on_later_page_during_cleanup(self):
        """Cleanup must abort and delete nothing if an unexpected file appears on page 2."""
        page1 = {
            "files": [{"id": self.valid_file_id, "name": "manifest_probe.json"}],
            "nextPageToken": "page_2_token",
        }
        page2 = {
            "files": [{"id": "unexpected_file_page2", "name": "secret.pdf"}],
            "nextPageToken": None,
        }

        def paginated_list(q=None, fields=None, pageToken=None, pageSize=None):
            req = MagicMock()
            if q and "'root' in parents" in q:
                req.execute.return_value = {
                    "files": [
                        {
                            "id": self.valid_folder_id,
                            "name": self.valid_folder_name,
                            "mimeType": "application/vnd.google-apps.folder",
                            "parents": [self.real_root_id],
                        }
                    ],
                    "nextPageToken": None,
                }
                return req
            if pageToken == "page_2_token":
                req.execute.return_value = page2
            else:
                req.execute.return_value = page1
            return req

        self.mock_service.files().list.side_effect = paginated_list

        with self.assertRaises(RuntimeError) as ctx:
            cleanup_validated_test_targets(self.mock_service, self.valid_folder_id, self.valid_file_id)

        self.assertIn("Unexpected files detected in test folder across pages", str(ctx.exception))
        self.mock_service.files().delete.assert_not_called()

    def test_cleanup_never_called_when_preflight_validation_fails(self):
        """Critical safeguard test: cleanup_validated_test_targets must NEVER be called if validation fails."""
        self._setup_valid_mocks()
        self.mock_service.files().get.side_effect = None
        req = MagicMock()
        req.execute.return_value = {
            "id": self.valid_folder_id,
            "name": "DangerousProductionVaultName",
            "mimeType": "application/vnd.google-apps.folder",
            "parents": [self.real_root_id],
            "trashed": False,
        }
        self.mock_service.files().get.return_value = req

        with patch("workers.processor.scripts.verify_cross_client.cleanup_validated_test_targets") as mock_cleanup:
            with self.assertRaises(ValueError):
                run_cross_client_verification(
                    service=self.mock_service,
                    folder_id=self.valid_folder_id,
                    file_id=self.valid_file_id,
                    forbidden_vault_id=self.production_vault_id,
                    skip_cleanup=False,
                )
            mock_cleanup.assert_not_called()

    def test_cleanup_failure_when_probe_file_deletion_fails_does_not_delete_folder(self):
        """If deleting the probe file fails, folder deletion must NOT be attempted and an error must be raised."""
        from googleapiclient.errors import HttpError

        self._setup_valid_mocks()

        delete_req = MagicMock()
        resp = MagicMock()
        resp.status = 500
        delete_req.execute.side_effect = HttpError(resp, b"Internal Drive Error")
        self.mock_service.files().delete.return_value = delete_req

        with self.assertRaises(RuntimeError) as ctx:
            cleanup_validated_test_targets(self.mock_service, self.valid_folder_id, self.valid_file_id)

        self.assertIn("could not delete probe file", str(ctx.exception))
        self.assertEqual(1, self.mock_service.files().delete.call_count)
        self.assertEqual(
            {"fileId": self.valid_file_id},
            self.mock_service.files().delete.call_args[1],
        )

    def test_successful_verification_and_cleanup_cycle(self):
        """Verifies full execution path from validation to update, re-read, and restricted cleanup."""
        self._setup_valid_mocks()

        payload_v1 = json.dumps({"version": 1, "test_id": "test_123"}).encode("utf-8")
        
        with patch("googleapiclient.http.MediaIoBaseDownload") as mock_downloader_cls:
            mock_dl = MagicMock()
            mock_dl.next_chunk.return_value = (None, True)
            mock_downloader_cls.return_value = mock_dl

            self.mock_service.files().get_media.return_value = MagicMock()
            
            update_req = MagicMock()
            update_req.execute.return_value = {"id": self.valid_file_id, "version": 2}
            self.mock_service.files().update.return_value = update_req

            del_req = MagicMock()
            del_req.execute.return_value = None
            self.mock_service.files().delete.return_value = del_req

            def mock_init(fh, req):
                if hasattr(mock_init, "called_once"):
                    fh.write(json.dumps({"version": 2, "updated_by": "worker_client_b"}).encode("utf-8"))
                else:
                    mock_init.called_once = True
                    fh.write(payload_v1)
                return mock_dl

            mock_downloader_cls.side_effect = mock_init

            success = run_cross_client_verification(
                service=self.mock_service,
                folder_id=self.valid_folder_id,
                file_id=self.valid_file_id,
                forbidden_vault_id=self.production_vault_id,
                skip_cleanup=False,
            )

            self.assertTrue(success)
            self.assertTrue(self.mock_service.files().update.called)
            self.assertEqual(2, self.mock_service.files().delete.call_count)
            calls = self.mock_service.files().delete.call_args_list
            self.assertEqual({"fileId": self.valid_file_id}, calls[0][1])
            self.assertEqual({"fileId": self.valid_folder_id}, calls[1][1])

    def test_missing_or_blank_production_vault_id_rejected(self):
        """Pre-flight validation must immediately reject missing or blank GDRIVE_FOLDER_ID before any Drive calls."""
        for invalid_vault_id in [None, "", "   "]:
            with self.subTest(invalid_vault_id=invalid_vault_id):
                with self.assertRaises(ValueError) as ctx:
                    validate_test_targets(
                        self.mock_service,
                        self.valid_folder_id,
                        self.valid_file_id,
                        invalid_vault_id,
                    )
                self.assertIn("Production vault ID (GDRIVE_FOLDER_ID) is missing or blank", str(ctx.exception))
                self.mock_service.files().get.assert_not_called()

    def test_folder_missing_properties_rejected(self):
        """Folder lacking 'reelix_type=cross_client_test' properties must be rejected."""
        self._setup_valid_mocks()
        self.mock_service.files().get.side_effect = None
        req = MagicMock()
        req.execute.return_value = {
            "id": self.valid_folder_id,
            "name": self.valid_folder_name,
            "mimeType": "application/vnd.google-apps.folder",
            "parents": [self.real_root_id],
            "trashed": False,
            "properties": {},
        }
        self.mock_service.files().get.return_value = req

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("missing required property 'reelix_type=cross_client_test'", str(ctx.exception))

    def test_folder_wrong_reelix_type_rejected(self):
        """Folder with invalid 'reelix_type' property must be rejected."""
        self._setup_valid_mocks()
        self.mock_service.files().get.side_effect = None
        req = MagicMock()
        req.execute.return_value = {
            "id": self.valid_folder_id,
            "name": self.valid_folder_name,
            "mimeType": "application/vnd.google-apps.folder",
            "parents": [self.real_root_id],
            "trashed": False,
            "properties": {"reelix_type": "production_job", "reelix_test_id": self.valid_test_id},
        }
        self.mock_service.files().get.return_value = req

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("missing required property 'reelix_type=cross_client_test'", str(ctx.exception))

    def test_folder_missing_reelix_test_id_rejected(self):
        """Folder lacking non-empty 'reelix_test_id' property must be rejected."""
        self._setup_valid_mocks()
        self.mock_service.files().get.side_effect = None
        req = MagicMock()
        req.execute.return_value = {
            "id": self.valid_folder_id,
            "name": self.valid_folder_name,
            "mimeType": "application/vnd.google-apps.folder",
            "parents": [self.real_root_id],
            "trashed": False,
            "properties": {"reelix_type": "cross_client_test", "reelix_test_id": "  "},
        }
        self.mock_service.files().get.return_value = req

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("missing non-empty 'reelix_test_id' property", str(ctx.exception))

    def test_file_missing_reelix_test_id_rejected(self):
        """Probe file lacking non-empty 'reelix_test_id' property must be rejected."""
        self._setup_valid_mocks()

        def files_get(fileId, fields=None):
            req = MagicMock()
            if fileId == "root":
                req.execute.return_value = {"id": self.real_root_id}
            elif fileId == self.valid_folder_id:
                req.execute.return_value = {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": [self.real_root_id],
                    "trashed": False,
                    "properties": {"reelix_type": "cross_client_test", "reelix_test_id": self.valid_test_id},
                }
            elif fileId == self.valid_file_id:
                req.execute.return_value = {
                    "id": self.valid_file_id,
                    "name": "manifest_probe.json",
                    "mimeType": "application/json",
                    "parents": [self.valid_folder_id],
                    "trashed": False,
                    "properties": {},
                }
            return req

        self.mock_service.files().get.side_effect = files_get

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("Target file is missing non-empty 'reelix_test_id' property", str(ctx.exception))

    def test_file_mismatched_reelix_test_id_rejected(self):
        """Probe file with reelix_test_id different from folder's reelix_test_id must be rejected."""
        self._setup_valid_mocks()

        def files_get(fileId, fields=None):
            req = MagicMock()
            if fileId == "root":
                req.execute.return_value = {"id": self.real_root_id}
            elif fileId == self.valid_folder_id:
                req.execute.return_value = {
                    "id": self.valid_folder_id,
                    "name": self.valid_folder_name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": [self.real_root_id],
                    "trashed": False,
                    "properties": {"reelix_type": "cross_client_test", "reelix_test_id": "folder_id_111"},
                }
            elif fileId == self.valid_file_id:
                req.execute.return_value = {
                    "id": self.valid_file_id,
                    "name": "manifest_probe.json",
                    "mimeType": "application/json",
                    "parents": [self.valid_folder_id],
                    "trashed": False,
                    "properties": {"reelix_test_id": "file_id_222"},
                }
            return req

        self.mock_service.files().get.side_effect = files_get

        with self.assertRaises(ValueError) as ctx:
            validate_test_targets(
                self.mock_service,
                self.valid_folder_id,
                self.valid_file_id,
                self.production_vault_id,
            )
        self.assertIn("does not match folder 'reelix_test_id'", str(ctx.exception))

    def test_successful_validation_with_matching_properties(self):
        """Successful validation when reelix_type and reelix_test_id match across folder and file."""
        self._setup_valid_mocks()
        folder, file_obj = validate_test_targets(
            self.mock_service,
            self.valid_folder_id,
            self.valid_file_id,
            self.production_vault_id,
        )
        self.assertEqual(self.valid_folder_id, folder["id"])
        self.assertEqual(self.valid_file_id, file_obj["id"])
        self.assertEqual("cross_client_test", folder["properties"]["reelix_type"])
        self.assertEqual(folder["properties"]["reelix_test_id"], file_obj["properties"]["reelix_test_id"])


class TestAndroidProbeHelper(unittest.TestCase):
    """Verifies token ingestion, ID validation, and error safety in android_probe_helper.py."""

    def test_secure_token_ingestion_from_environment(self):
        with patch.dict(os.environ, {"ANDROID_ACCESS_TOKEN": "mock_valid_token_xyz123"}):
            token = get_access_token()
            self.assertEqual("mock_valid_token_xyz123", token)

    def test_secure_token_ingestion_missing_raises_error_without_token(self):
        with patch.dict(os.environ, {}, clear=True):
            with patch("sys.stdin.isatty", return_value=False):
                with self.assertRaises(ValueError) as ctx:
                    get_access_token()
                self.assertIn("Android OAuth access token not found", str(ctx.exception))

    def test_validate_drive_id_valid(self):
        valid_id = "1a2B3c4D5e6F7g8H9i0J_kLmNoPqRsTuVwXyZ"
        res = validate_drive_id(valid_id, "folder")
        self.assertEqual(valid_id, res)

    def test_validate_drive_id_invalid_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_drive_id("short", "folder")
        self.assertIn("unexpected folder ID format", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
