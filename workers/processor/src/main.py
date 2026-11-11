"""Main CLI entrypoint for Reelix cloud processing worker (Hybrid execution: GitHub Actions & Mac Local)."""

from __future__ import annotations

import argparse
import logging
import os
import sys

from .config import WorkerConfig
from .pipeline import CloudProcessingPipeline
from .storage.drive_client import GoogleDriveStorageClient
from .storage.firestore_client import FirestoreJobCoordinator
from .storage.mock_storage import MockStorageClient

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("reelix_worker")


def parse_args(args: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Reelix Hybrid Cloud Processing Worker")
    parser.add_argument(
        "--confirm-local",
        "--local",
        "--mac-local",
        action="store_true",
        dest="confirm_local",
        help="Legacy flag (local production claiming is disabled; use REELIX_MOCK_MODE=1 for local development)",
    )
    parser.add_argument(
        "--worker-id",
        type=str,
        default=None,
        help="Custom worker / runner identity (default: gha-{run_id}-{attempt} or local-{pid})",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Maximum number of queued jobs to process in this run",
    )
    parser.add_argument(
        "--firestore-project",
        type=str,
        default=None,
        help="Google Cloud project ID for Cloud Firestore coordination",
    )
    parser.add_argument(
        "--experimental-firestore",
        action="store_true",
        dest="experimental_firestore",
        help="Explicitly opt in to experimental Firestore coordination",
    )
    return parser.parse_args(args)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    logger.info("Initializing Reelix Hybrid Cloud Processing Worker...")
    config = WorkerConfig.from_env()

    if args.worker_id:
        config.runner_id = args.worker_id
    if args.limit:
        config.max_jobs_per_run = args.limit
    if args.firestore_project:
        config.firestore_project_id = args.firestore_project

    if config.firestore_project_id and not args.experimental_firestore:
        logger.error(
            "FATAL: FIRESTORE_PROJECT_ID is set ('%s'), but Firestore coordination is experimental "
            "and disconnected from Android production submission. Google Drive is the sole authoritative queue. "
            "To force experimental Firestore coordination, pass --experimental-firestore explicitly.",
            config.firestore_project_id,
        )
        return 1

    has_oauth = bool(config.gdrive_refresh_token and config.gdrive_client_id and config.gdrive_client_secret)
    has_sa = bool(config.gdrive_service_account_json or config.gdrive_service_account_path)
    has_drive_creds = has_oauth or has_sa

    is_github_actions = bool(os.getenv("GITHUB_RUN_ID"))
    if not is_github_actions and not config.mock_mode and has_drive_creds:
        logger.error(
            "========================================================================\n"
            "FATAL: Production job claiming from the local Mac worker is DISABLED.\n"
            "GitHub Actions is the sole authorized production worker to guarantee single-active-worker\n"
            "serialization under repository concurrency group 'reelix-processing'.\n"
            "Google Drive API does not provide atomic distributed locks across independent compute hosts.\n"
            "To prevent split-brain write conflicts with active GitHub Actions schedules,\n"
            "local worker execution against production Google Drive is forbidden.\n\n"
            "For local pipeline development and testing, set REELIX_MOCK_MODE=1.\n"
            "========================================================================"
        )
        return 1

    # Initialize persistent storage (Google Drive or Mock)
    if config.mock_mode or not has_drive_creds:
        logger.info("Running in local mock storage mode (REELIX_MOCK_MODE or no Drive credentials)")
        storage = MockStorageClient(root_dir=config.work_dir / "storage")
    else:
        logger.info("Connecting to Google Drive persistent storage (folder_id=%s)...", config.gdrive_folder_id)
        storage = GoogleDriveStorageClient(config)

    # Initialize Firestore coordinator (if explicitly opted-in)
    coordinator = None
    if config.firestore_project_id and args.experimental_firestore:
        try:
            logger.info("Initializing Cloud Firestore job coordinator (project=%s)...", config.firestore_project_id)
            coordinator = FirestoreJobCoordinator(project_id=config.firestore_project_id)
        except Exception as e:
            logger.warning("Failed to initialize Firestore coordinator (%s); falling back to direct storage queue", e)
        except Exception as e:
            logger.warning("Failed to initialize Firestore coordinator (%s); falling back to direct storage queue", e)

    pipeline = CloudProcessingPipeline(config=config, storage=storage, coordinator=coordinator)
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

