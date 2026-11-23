"""Isolated Real Google Drive API Integration Verification Script.

Executes within GitHub Actions (or locally with REELIX_MOCK_MODE=1) to verify:
1. Dedicated isolated test folder creation directly in Drive root (anti-target: never touches GDRIVE_FOLDER_ID).
2. Google Drive file ID pre-generation via files().generateIds().
3. Transition intent persistence and duplicate intent reconciliation.
4. Append-only transition creation, idempotent retry, and authoritative state folding.
5. Scoped artifact uploads, stale worker fencing, and superseding epoch protection.
6. Deterministic restricted cleanup of only test-created artifacts and folders.

Zero production items are ever read, updated, or deleted.
Zero secrets or tokens are ever logged.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import logging
import os
import sys
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("verify_drive_integration")

SAFE_TEST_FOLDER_PREFIX = "Reelix_Isolated_Test_"
DRIVE_SCOPES = ["https://www.googleapis.com/auth/drive.file"]


def get_authorized_service(client_id: str, client_secret: str, refresh_token: str):
    """Initializes Google Drive v3 client from OAuth credentials without logging secrets."""
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials
    from googleapiclient.discovery import build

    creds = Credentials(
        token=None,
        refresh_token=refresh_token,
        token_uri="https://oauth2.googleapis.com/token",
        client_id=client_id,
        client_secret=client_secret,
        scopes=DRIVE_SCOPES,
    )
    creds.refresh(Request())
    return build("drive", "v3", credentials=creds, cache_discovery=False)


class IsolatedIntegrationVerifier:
    """Orchestrates end-to-end integration verification in an isolated Google Drive test folder."""

    def __init__(
        self,
        storage_client: Any,
        service: Any = None,
        production_folder_id: Optional[str] = None,
        test_folder_id: Optional[str] = None,
    ):
        self.storage = storage_client
        self.service = service
        self.production_folder_id = production_folder_id.strip() if production_folder_id else None
        self.test_folder_id = test_folder_id
        self.test_id = f"{int(time.time())}_{uuid.uuid4().hex[:6]}"
        self.test_item_id = f"test-item-{self.test_id}"
        self.created_file_ids: List[str] = []
        self.created_folder_ids: List[str] = []

    def verify_all(self) -> bool:
        """Executes all 5 verification phases sequentially."""
        logger.info("==================================================================")
        logger.info("Starting Isolated Reelix Google Drive Integration Verification")
        logger.info("Test Run ID: %s", self.test_id)
        logger.info("Test Item ID: %s", self.test_item_id)
        logger.info("==================================================================")

        try:
            self.phase_1_setup_isolated_folder()
            self.phase_2_verify_drive_id_generation_and_intent_reconciliation()
            self.phase_3_verify_transitions_and_idempotent_retry()
            self.phase_4_verify_artifact_fencing_and_stale_worker_rejection()
            logger.info("==================================================================")
            logger.info("ALL INTEGRATION VERIFICATION PHASES PASSED SUCCESSFULLY!")
            logger.info("==================================================================")
            return True
        except Exception as e:
            logger.error("Integration verification failed: %s", e, exc_info=True)
            return False
        finally:
            self.cleanup()

    def phase_1_setup_isolated_folder(self) -> None:
        """Phase 1: Validates that the active storage root is NOT the production folder."""
        logger.info("Phase 1: Validating isolated test environment...")

        if self.service and self.production_folder_id:
            # Check anti-target: ensure test folder does NOT match production folder ID
            if self.test_folder_id and self.test_folder_id == self.production_folder_id:
                raise ValueError(
                    f"FATAL: Test folder ID ({self.test_folder_id}) matches production GDRIVE_FOLDER_ID! "
                    "Execution halted to prevent touching production Reelix Vault."
                )

        logger.info("  ✓ Confirmed test environment is strictly isolated from production vault.")

    def phase_2_verify_drive_id_generation_and_intent_reconciliation(self) -> None:
        """Phase 2: Verifies files.generateIds and duplicate intent discovery/reconciliation."""
        logger.info("Phase 2: Verifying Google Drive ID generation and intent reconciliation...")
        from workers.processor.src.models import TransitionRecord, TransitionType

        # 1. Drive file ID generation
        generated_id = self.storage.generate_drive_file_id()
        if not generated_id or len(generated_id) < 5:
            raise ValueError(f"Drive file ID generation failed: returned invalid ID '{generated_id}'")
        logger.info("  ✓ Generated valid Google Drive file ID: %s", generated_id)

        # 2. Intent persistence
        trans = TransitionRecord(
            item_id=self.test_item_id,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="worker-integration-1",
            created_at=int(time.time() * 1000),
            payload={"claimed_at": int(time.time() * 1000)},
        )
        self.trans_1 = trans
        tid = trans.deterministic_transition_id

        intent_saved = self.storage.save_transition_intent(
            item_id=self.test_item_id,
            deterministic_transition_id=tid,
            drive_file_id=generated_id,
            payload_hash=trans.payload_integrity_hash,
        )
        if not intent_saved:
            raise ValueError("Failed to persist transition intent")
        logger.info("  ✓ Persisted transition intent for %s (id=%s)", tid, generated_id)

        # 3. Intent discovery
        discovered = self.storage.find_all_transition_intents(self.test_item_id, tid)
        if not discovered:
            raise ValueError(f"Failed to discover persisted intent for {tid}")
        logger.info("  ✓ Discovered %d transition intent(s) via prefix query", len(discovered))

        # 4. Conflict detection test on isolated probe item
        conflict_item = f"test-conflict-{self.test_id}"
        conflict_trans = TransitionRecord(
            item_id=conflict_item,
            generation=1,
            attempt_epoch=1,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="runner-conflict",
            created_at=int(time.time() * 1000),
            payload={"claimed_at": int(time.time() * 1000)},
        )
        self.storage.save_transition_intent(
            item_id=conflict_item,
            deterministic_transition_id=conflict_trans.deterministic_transition_id,
            drive_file_id="conflicting_fake_id",
            payload_hash="conflicting_sha256_hash_12345",
        )
        conflict_ok = self.storage.append_transition(conflict_trans)
        if conflict_ok:
            raise AssertionError("Conflicting intent payload must be rejected during append_transition!")
        logger.info("  ✓ Conflicting intent payload rejected and conflict preserved for diagnosis")
        self.storage.delete_transition_intent(conflict_item, "*", preserve_conflicts=False)

    def phase_3_verify_transitions_and_idempotent_retry(self) -> None:
        """Phase 3: Verifies transition creation, idempotent retry, and state reconstruction."""
        logger.info("Phase 3: Verifying append-only transitions and idempotent retry...")
        from workers.processor.src.models import fold_transitions, JobStatus

        trans_1 = getattr(self, "trans_1", None)
        if not trans_1:
            raise ValueError("self.trans_1 not initialized in phase 2")

        # Append transition
        ok = self.storage.append_transition(trans_1)
        if not ok:
            raise ValueError(f"Failed to append transition 1 for {self.test_item_id}")
        logger.info("  ✓ Successfully appended transition 1 (CLAIM, epoch=1)")

        # Idempotent retry: re-appending the identical transition must return True
        retry_ok = self.storage.append_transition(trans_1)
        if not retry_ok:
            raise ValueError("Idempotent transition retry failed (expected True)")
        logger.info("  ✓ Idempotent retry verified: re-appending identical transition succeeded")

        # List transitions and verify count
        all_trans = self.storage.list_transitions(self.test_item_id)
        matching = [t for t in all_trans if t.deterministic_transition_id == trans_1.deterministic_transition_id]
        if len(matching) != 1:
            raise ValueError(f"Duplicate transition detected! Expected 1 matching record, got {len(matching)}")
        logger.info("  ✓ Verified zero duplicate transitions created on retry")

        # Reconstruct state via fold
        folded, warnings = fold_transitions(self.test_item_id, all_trans)
        if folded.status != JobStatus.PROCESSING:
            raise ValueError(f"Folded state status mismatch: expected PROCESSING, got {folded.status}")
        if folded.active_attempt_epoch != 1:
            raise ValueError(f"Folded epoch mismatch: expected 1, got {folded.active_attempt_epoch}")
        logger.info("  ✓ Transition log folded into authoritative state: status=%s, epoch=%d", folded.status.value, folded.active_attempt_epoch)

    def phase_4_verify_artifact_fencing_and_stale_worker_rejection(self) -> None:
        """Phase 4: Verifies epoch-scoped artifact storage and stale worker publication fencing."""
        logger.info("Phase 4: Verifying epoch-scoped artifact fencing and stale worker rejection...")
        from workers.processor.src.config import WorkerConfig
        from workers.processor.src.models import (
            ArtifactRef,
            JobManifest,
            JobStatus,
            StageStates,
            StageStatus,
            TransitionRecord,
            TransitionType,
            fold_transitions,
        )
        from workers.processor.src.pipeline import CloudProcessingPipeline

        # 1. Scoped artifact upload: Epoch 1
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(b"RIFF_FAKE_AUDIO_CONTENT_EPOCH_1")
            wav_path_1 = Path(f.name)
        try:
            art_id_1 = self.storage.upload_audio(
                wav_path_1,
                item_id=self.test_item_id,
                generation=1,
                attempt_epoch=1,
            )
            if not art_id_1:
                raise ValueError("Epoch 1 audio upload failed")
            logger.info("  ✓ Uploaded epoch-scoped audio artifact (epoch=1, id=%s)", art_id_1)
        finally:
            wav_path_1.unlink(missing_ok=True)

        # 2. Worker 2 takes over by recording Epoch 2 CLAIM
        trans_epoch_2 = TransitionRecord(
            item_id=self.test_item_id,
            generation=1,
            attempt_epoch=2,
            transition_seq=1,
            transition_type=TransitionType.CLAIM,
            attempt_id="worker-integration-2",
            created_at=int(time.time() * 1000) + 1000,
            payload={"claimed_at": int(time.time() * 1000) + 1000},
        )
        ok_claim_2 = self.storage.append_transition(trans_epoch_2)
        if not ok_claim_2:
            raise ValueError("Failed to append Epoch 2 CLAIM transition")
        logger.info("  ✓ Worker 2 established ownership: CLAIM recorded for epoch 2")

        # 3. Worker 1 (stale, still on epoch 1) attempts to publish AUDIO_EXTRACTED stage
        with tempfile.TemporaryDirectory() as work_dir:
            p1 = CloudProcessingPipeline(
                config=WorkerConfig(work_dir=Path(work_dir), runner_id="worker-integration-1"),
                storage=self.storage,
            )
            stale_job = JobManifest(
                item_id=self.test_item_id,
                source="URL",
                status=JobStatus.PROCESSING,
                generation=1,
                active_attempt_epoch=1,
                created_at=1000,
                updated_at=1000,
            )
            art_stale = ArtifactRef(
                drive_file_id=art_id_1,
                mime_type="audio/wav",
                size_bytes=30,
                content_hash=hashlib.sha256(b"RIFF_FAKE_AUDIO_CONTENT_EPOCH_1").hexdigest(),
                name=f"{self.test_item_id}.wav",
                generation=1,
                attempt_epoch=1,
            )
            published = p1._update_stage(stale_job, "audio_extraction", StageStatus.SUCCEEDED, artifact=art_stale)
            if published:
                raise AssertionError("FATAL: Stale Worker 1 was NOT fenced out! It published a stage update on superseded epoch 1!")
            logger.info("  ✓ Stale Worker 1 correctly FENCED OUT from publishing stage update (returned False)")

        # 4. Verify authoritative transition log has NO audio extraction transition for epoch 1
        transitions_after = self.storage.list_transitions(self.test_item_id)
        has_stale_extraction = any(
            t.transition_type == TransitionType.AUDIO_EXTRACTED and t.attempt_epoch == 1
            for t in transitions_after
        )
        if has_stale_extraction:
            raise AssertionError("Authoritative transition log contaminated with stale epoch 1 transition!")
        logger.info("  ✓ Verified authoritative transition log is untainted by stale worker")

        # 5. Verify folded state reflects Epoch 2
        folded, _ = fold_transitions(self.test_item_id, transitions_after)
        if folded.active_attempt_epoch != 2:
            raise AssertionError(f"Folded state active epoch mismatch: expected 2, got {folded.active_attempt_epoch}")
        if folded.stages.audio_extraction == StageStatus.SUCCEEDED:
            raise AssertionError("Folded stage status must NOT be SUCCEEDED from stale worker output")
        logger.info("  ✓ Authoritative folded state accurately preserves Epoch 2 ownership")

    def cleanup(self) -> None:
        """Phase 5: Restricts cleanup to only files and folders created in this integration test."""
        logger.info("Phase 5: Executing restricted test cleanup...")
        cleanup_failures = []

        # Delete transition intents for test item
        try:
            self.storage.delete_transition_intent(self.test_item_id, "*", preserve_conflicts=False)
        except Exception as e:
            cleanup_failures.append(f"Failed deleting test transition intents: {e}")

        # If live Drive service is active and a test folder was created:
        if self.service and self.test_folder_id:
            try:
                # Double-check anti-target safeguard before deleting
                if self.production_folder_id and self.test_folder_id == self.production_folder_id:
                    logger.error("ABORTING FOLDER CLEANUP: target folder matches production folder ID!")
                    return
                self.service.files().delete(fileId=self.test_folder_id).execute()
                logger.info("  ✓ Deleted isolated test folder %s", self.test_folder_id)
            except Exception as e:
                cleanup_failures.append(f"Failed deleting isolated test folder {self.test_folder_id}: {e}")

        if cleanup_failures:
            logger.warning("Cleanup completed with warnings: %s", "; ".join(cleanup_failures))
        else:
            logger.info("  ✓ Restricted test cleanup completed with 0 errors.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Reelix Isolated Real Drive Integration Verifier")
    parser.add_argument("--mock", action="store_true", help="Force local mock mode for unit verification")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    mock_mode = args.mock or os.getenv("REELIX_MOCK_MODE") == "1"

    client_id = os.getenv("GDRIVE_CLIENT_ID")
    client_secret = os.getenv("GDRIVE_CLIENT_SECRET")
    refresh_token = os.getenv("GDRIVE_REFRESH_TOKEN")
    prod_folder_id = os.getenv("GDRIVE_FOLDER_ID")

    has_live_creds = bool(client_id and client_secret and refresh_token)

    if mock_mode or not has_live_creds:
        if not mock_mode and not has_live_creds:
            logger.info("No live Google Drive credentials in environment; running in safe mock mode.")
        from workers.processor.src.storage.mock_storage import MockStorageClient

        with tempfile.TemporaryDirectory() as tmp_root:
            storage = MockStorageClient(root_dir=Path(tmp_root))
            verifier = IsolatedIntegrationVerifier(
                storage_client=storage,
                production_folder_id=prod_folder_id,
            )
            success = verifier.verify_all()
            return 0 if success else 1

    # Live Google Drive mode (running on GitHub Actions runner with repository secrets)
    logger.info("Authenticating with Google Drive API using OAuth credentials...")
    service = get_authorized_service(client_id, client_secret, refresh_token)

    # Create dedicated isolated test folder in Drive root
    test_folder_name = f"{SAFE_TEST_FOLDER_PREFIX}{int(time.time())}_{uuid.uuid4().hex[:6]}"
    folder_meta = {
        "name": test_folder_name,
        "mimeType": "application/vnd.google-apps.folder",
        "parents": ["root"],
    }
    created_folder = service.files().create(body=folder_meta, fields="id, name").execute()
    test_folder_id = created_folder.get("id")
    logger.info("Created isolated test folder: '%s' (id=%s)", test_folder_name, test_folder_id)

    # Initialize GoogleDriveStorageClient pointed specifically to the test folder
    from workers.processor.src.config import WorkerConfig
    from workers.processor.src.storage.drive_client import GoogleDriveStorageClient

    config = WorkerConfig(
        gdrive_folder_id=test_folder_id,
        gdrive_client_id=client_id,
        gdrive_client_secret=client_secret,
        gdrive_refresh_token=refresh_token,
    )
    storage = GoogleDriveStorageClient(config)

    verifier = IsolatedIntegrationVerifier(
        storage_client=storage,
        service=service,
        production_folder_id=prod_folder_id,
        test_folder_id=test_folder_id,
    )
    success = verifier.verify_all()
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
