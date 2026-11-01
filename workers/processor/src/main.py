"""Main CLI entrypoint for Reelix GitHub Actions cloud processing worker."""

from __future__ import annotations

import logging
import os
import sys

from .config import WorkerConfig
from .pipeline import CloudProcessingPipeline
from .storage.drive_client import GoogleDriveStorageClient
from .storage.mock_storage import MockStorageClient

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("reelix_worker")


def main() -> int:
    logger.info("Initializing Reelix Cloud Processing Worker...")
    config = WorkerConfig.from_env()

    has_oauth = bool(config.gdrive_refresh_token and config.gdrive_client_id and config.gdrive_client_secret)
    has_sa = bool(config.gdrive_service_account_json or config.gdrive_service_account_path)
    has_drive_creds = has_oauth or has_sa

    is_github_actions = bool(os.getenv("GITHUB_RUN_ID"))
    if not is_github_actions and not config.mock_mode and has_drive_creds:
        allow_local = os.getenv("ALLOW_LOCAL_WORKER") == "1" or "--confirm-local" in sys.argv
        if not allow_local:
            logger.warning(
                "========================================================================\n"
                "WARNING: Attempting to run cloud processor locally outside GitHub Actions.\n"
                "The Google Drive job queue is designed for single-writer serialization via\n"
                "the GitHub Actions workflow concurrency group (group: reelix-processing).\n"
                "Running a local worker while scheduled GitHub Actions runners are active\n"
                "can cause manifest write conflicts.\n\n"
                "To run locally, explicitly pass --confirm-local or set ALLOW_LOCAL_WORKER=1.\n"
                "========================================================================"
            )
            return 1

    if config.mock_mode or not has_drive_creds:
        logger.info("Running in local mock storage mode (REELIX_MOCK_MODE or no Drive credentials)")
        storage = MockStorageClient(root_dir=config.work_dir / "storage")
    else:
        logger.info("Connecting to Google Drive persistent storage (folder_id=%s)...", config.gdrive_folder_id)
        storage = GoogleDriveStorageClient(config)

    pipeline = CloudProcessingPipeline(config=config, storage=storage)
    summary = pipeline.run_batch()

    logger.info(
        "Execution complete: %d queued found, %d processed (%d succeeded, %d failed)",
        summary.total_found,
        summary.processed,
        summary.succeeded,
        summary.failed,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

