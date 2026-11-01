"""Main CLI entrypoint for Reelix GitHub Actions cloud processing worker."""

from __future__ import annotations

import logging
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

    if config.mock_mode or (not config.gdrive_service_account_json and not config.gdrive_service_account_path):
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

