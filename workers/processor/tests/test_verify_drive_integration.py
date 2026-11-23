"""Unit tests for the isolated Google Drive integration verifier script."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from workers.processor.scripts.verify_drive_integration import (
    IsolatedIntegrationVerifier,
    main,
)
from workers.processor.src.storage.mock_storage import MockStorageClient


class TestVerifyDriveIntegration(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.TemporaryDirectory()
        self.storage = MockStorageClient(root_dir=Path(self.test_dir.name))

    def tearDown(self):
        self.test_dir.cleanup()

    def test_isolated_verifier_runs_all_phases_in_mock_mode(self):
        """Verifies that all 5 integration phases succeed under MockStorageClient."""
        verifier = IsolatedIntegrationVerifier(
            storage_client=self.storage,
            production_folder_id="prod_vault_folder_12345",
            test_folder_id="isolated_test_folder_67890",
        )
        success = verifier.verify_all()
        self.assertTrue(success, "All phases of isolated integration verification must succeed")

    def test_anti_target_safeguard_aborts_if_folder_matches_production(self):
        """Verifier must abort immediately if test folder ID matches production folder ID."""
        verifier = IsolatedIntegrationVerifier(
            storage_client=self.storage,
            service=MagicMock(),
            production_folder_id="dangerous_prod_vault_id",
            test_folder_id="dangerous_prod_vault_id",
        )
        success = verifier.verify_all()
        self.assertFalse(success, "Must reject execution when test folder matches production vault")

    def test_main_cli_mock_flag(self):
        """CLI main() with --mock runs and returns 0."""
        code = main(["--mock"])
        self.assertEqual(0, code)


if __name__ == "__main__":
    unittest.main()
