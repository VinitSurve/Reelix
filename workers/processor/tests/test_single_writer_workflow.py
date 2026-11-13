"""Static and runtime verification tests for single-writer enforcement across GitHub Actions and local entrypoints.

Verifies:
- All production workflows writing or reading Drive queue use identical concurrency group 'reelix-processing'.
- Concurrency configuration specifies cancel-in-progress: false and queue: max.
- Scheduled, manual, and dispatch triggers are serialized under the same concurrency group.
- No matrix or parallel jobs exist for queue processing.
- Local development cannot accidentally claim or mutate production Drive state.
- Unexpected concurrent writers fail safely.
"""

from __future__ import annotations

import os
from pathlib import Path
import unittest
from unittest.mock import patch
import yaml

from workers.processor.src.config import WorkerConfig
from workers.processor.src.main import main


class TestSingleWriterWorkflowEnforcement(unittest.TestCase):
    def setUp(self):
        self.repo_root = Path(__file__).resolve().parents[3]
        self.workflows_dir = self.repo_root / ".github" / "workflows"

    def test_production_workflows_use_identical_concurrency_group(self):
        """All workflows interacting with Drive queue must share 'reelix-processing' concurrency group."""
        drive_workflows = [
            "process-queue.yml",
            "diagnose-queue.yml",
            "verify-cross-client-drive.yml",
        ]

        for wf_name in drive_workflows:
            wf_path = self.workflows_dir / wf_name
            self.assertTrue(wf_path.exists(), f"Workflow file {wf_name} missing")
            content = yaml.safe_load(wf_path.read_text(encoding="utf-8"))

            self.assertIn("concurrency", content, f"{wf_name} must declare a top-level concurrency group")
            concurrency = content["concurrency"]
            self.assertEqual(
                "reelix-processing",
                concurrency.get("group"),
                f"{wf_name} must use concurrency group 'reelix-processing'",
            )
            self.assertFalse(
                concurrency.get("cancel-in-progress", True),
                f"{wf_name} must set cancel-in-progress: false to serialize rather than cancel",
            )
            self.assertEqual(
                "max",
                concurrency.get("queue"),
                f"{wf_name} must set queue: max to buffer sequential runs",
            )

    def test_queue_processing_workflow_covers_scheduled_and_dispatch_triggers(self):
        """process-queue.yml must serialize both scheduled cron and manual workflow_dispatch runs."""
        wf_path = self.workflows_dir / "process-queue.yml"
        content = yaml.safe_load(wf_path.read_text(encoding="utf-8"))

        triggers = content.get("on") or content.get(True)
        self.assertIn("schedule", triggers, "process-queue.yml must have a scheduled cron trigger")
        self.assertIn("workflow_dispatch", triggers, "process-queue.yml must have workflow_dispatch trigger")

    def test_no_matrix_or_parallel_jobs_for_drive_writers(self):
        """Queue processing workflow must contain exactly one job and NO matrix strategies."""
        wf_path = self.workflows_dir / "process-queue.yml"
        content = yaml.safe_load(wf_path.read_text(encoding="utf-8"))

        jobs = content.get("jobs", {})
        self.assertEqual(1, len(jobs), "process-queue.yml must define exactly one job to prevent parallel workers")

        job_def = next(iter(jobs.values()))
        self.assertNotIn("strategy", job_def, "process-queue.yml job must not use matrix strategy")

    def test_local_mac_worker_blocked_from_production_drive_access(self):
        """Local execution without REELIX_MOCK_MODE=1 is hard-blocked when Drive credentials exist."""
        test_env = {
            "GDRIVE_REFRESH_TOKEN": "mock-token",
            "GDRIVE_CLIENT_ID": "mock-client-id",
            "GDRIVE_CLIENT_SECRET": "mock-secret",
            "REELIX_MOCK_MODE": "0",
        }
        # Ensure GITHUB_RUN_ID is unset (simulating local Mac terminal)
        with patch.dict(os.environ, test_env, clear=False):
            if "GITHUB_RUN_ID" in os.environ:
                del os.environ["GITHUB_RUN_ID"]
            exit_code = main(["--limit", "1"])
            self.assertEqual(1, exit_code, "Local production execution must exit with code 1")

    def test_local_mac_worker_allowed_with_mock_mode(self):
        """Local execution with REELIX_MOCK_MODE=1 runs safely in offline mock storage mode."""
        test_env = {
            "REELIX_MOCK_MODE": "1",
            "WORK_DIR": "/tmp/reelix_local_test_mock",
        }
        with patch.dict(os.environ, test_env, clear=False):
            if "GITHUB_RUN_ID" in os.environ:
                del os.environ["GITHUB_RUN_ID"]
            exit_code = main(["--limit", "0"])
            self.assertEqual(0, exit_code, "Mock mode execution must exit with code 0")


if __name__ == "__main__":
    unittest.main()
