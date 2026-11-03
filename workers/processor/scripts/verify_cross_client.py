"""Cross-Client Google Drive Access Verification Script (Worker Client B).

Executes inside GitHub Actions to verify cross-client interoperability under drive.file scope.
Requires:
- GDRIVE_CLIENT_ID
- GDRIVE_CLIENT_SECRET
- GDRIVE_REFRESH_TOKEN
- GDRIVE_FOLDER_ID (used strictly as an anti-target safeguard to reject production vault)

Strict Safeguards:
1. Rejects execution if folder_id or file_id matches GDRIVE_FOLDER_ID.
2. Validates that folder name starts with 'Reelix_CrossClient_Test_'.
3. Resolves the real Drive root ID (via Drive API) and verifies folder is directly under root.
4. Validates that file_id exists, is named 'manifest_probe.json', has MIME 'application/json', and belongs to folder_id.
5. Uses complete pagination (following nextPageToken) across all files.list() calls.
6. Verifies test folder contains ONLY the expected probe file across all pages.
7. Validation runs OUTSIDE the cleanup block; cleanup NEVER runs if validation fails.
8. Cleanup deletes only the exact validated file, then the exact validated folder.
9. If file deletion fails, folder deletion is NOT attempted, and cleanup failure causes non-zero exit.
10. Zero secrets, tokens, or auth headers are logged.
"""

from __future__ import annotations

import argparse
import io
import json
import logging
import os
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("verify_cross_client")

DRIVE_SCOPE = ["https://www.googleapis.com/auth/drive.file"]
SAFE_FOLDER_PREFIX = "Reelix_CrossClient_Test_"
EXPECTED_FILE_NAME = "manifest_probe.json"
EXPECTED_FILE_MIME = "application/json"


def get_authorized_service(client_id: str, client_secret: str, refresh_token: str):
    """Initializes Google Drive v3 client from OAuth refresh token without exposing credentials."""
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials
    from googleapiclient.discovery import build

    creds = Credentials(
        token=None,
        refresh_token=refresh_token,
        token_uri="https://oauth2.googleapis.com/token",
        client_id=client_id,
        client_secret=client_secret,
        scopes=DRIVE_SCOPE,
    )
    creds.refresh(Request())
    return build("drive", "v3", credentials=creds, cache_discovery=False)


def resolve_root_folder_id(service) -> str:
    """Resolves the user's actual Google Drive root folder ID using the Drive API.

    In Google Drive API v3, querying files().get(fileId="root") returns the real alphanumeric ID
    of the user's My Drive root folder, which matches the 'parents' array of top-level items.
    If the actual root folder ID cannot be resolved, an exception is raised to abort verification.
    """
    from googleapiclient.errors import HttpError

    try:
        root_file = service.files().get(fileId="root", fields="id").execute()
        root_id = (root_file.get("id") or "").strip()
        if root_id:
            logger.info("  ✓ Resolved Drive actual root folder ID: %s", root_id)
            return root_id
    except HttpError as e:
        logger.error("Could not resolve 'root' folder ID directly (HTTP %d: %s). Aborting verification.", e.resp.status, e.reason)
        raise RuntimeError(f"Failed to resolve Drive actual root folder ID: HTTP {e.resp.status} - {e.reason}") from e
    except Exception as e:
        logger.error("Unexpected error resolving 'root' ID: %s. Aborting verification.", e)
        raise RuntimeError(f"Unexpected error resolving Drive actual root folder ID: {e}") from e

    raise RuntimeError("Drive API returned an empty or invalid root folder ID. Aborting verification.")


def list_all_children(
    service,
    folder_id: str,
    fields: str = "id, name, mimeType, parents, properties",
) -> List[Dict[str, Any]]:
    """Exhaustively lists all child items in folder_id following nextPageToken across all pages."""
    clean_folder = folder_id.strip()
    q = f"'{clean_folder}' in parents and trashed = false"
    children: List[Dict[str, Any]] = []
    page_token: Optional[str] = None
    page_count = 0

    while True:
        page_count += 1
        res = service.files().list(
            q=q,
            fields=f"nextPageToken, files({fields})",
            pageToken=page_token,
            pageSize=100,
        ).execute()

        files_on_page = res.get("files", [])
        children.extend(files_on_page)
        page_token = res.get("nextPageToken")

        if not page_token:
            break

    if page_count > 1:
        logger.info("  (Paginated list: traversed %d pages, retrieved %d total items)", page_count, len(children))

    return children


def validate_test_targets(
    service,
    folder_id: str,
    file_id: str,
    forbidden_vault_id: str,
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """Applies strict pre-flight target validation.

    Must succeed completely before any verification or cleanup logic is permitted.
    """
    clean_vault = (forbidden_vault_id or "").strip()
    if not clean_vault:
        raise ValueError(
            "SAFETY VIOLATION: Production vault ID (GDRIVE_FOLDER_ID) is missing or blank. "
            "Verification strictly requires GDRIVE_FOLDER_ID to enforce production-vault protection."
        )

    clean_folder = (folder_id or "").strip()
    clean_file = (file_id or "").strip()

    if not clean_folder:
        raise ValueError("Target folder_id must not be empty.")
    if not clean_file:
        raise ValueError("Target file_id must not be empty.")

    if clean_folder == clean_vault:
        raise ValueError(
            f"SAFETY VIOLATION: Supplied folder_id matches production GDRIVE_FOLDER_ID ({clean_folder}). "
            "Refusing to execute verification against production Reelix vault."
        )
    if clean_file == clean_vault:
        raise ValueError(
            f"SAFETY VIOLATION: Supplied file_id matches production GDRIVE_FOLDER_ID ({clean_file}). "
            "Refusing to execute verification against production Reelix vault."
        )

    from googleapiclient.errors import HttpError

    # 1. Validate Folder
    try:
        folder = service.files().get(
            fileId=clean_folder,
            fields="id, name, mimeType, parents, trashed, properties",
        ).execute()
    except HttpError as e:
        logger.error("Failed to access folder %s (HTTP %d): %s", clean_folder, e.resp.status, e.reason)
        raise

    if folder.get("trashed", False):
        raise ValueError(f"Target folder {clean_folder} is trashed.")

    if folder.get("mimeType") != "application/vnd.google-apps.folder":
        raise ValueError(f"Target ID {clean_folder} is not a folder (mimeType={folder.get('mimeType')}).")

    folder_name = folder.get("name", "")
    if not folder_name.startswith(SAFE_FOLDER_PREFIX):
        raise ValueError(
            f"SAFETY VIOLATION: Target folder name '{folder_name}' does not start with required prefix '{SAFE_FOLDER_PREFIX}'. "
            "Verification strictly refuses to operate on non-test folders."
        )

    # Validate Folder properties
    folder_props = folder.get("properties") or {}
    reelix_type = folder_props.get("reelix_type")
    if reelix_type != "cross_client_test":
        raise ValueError(
            f"SAFETY VIOLATION: Target folder is missing required property 'reelix_type=cross_client_test' "
            f"(found: {reelix_type!r}). Refusing to operate on non-test resources."
        )

    folder_test_id = (folder_props.get("reelix_test_id") or "").strip()
    if not folder_test_id:
        raise ValueError(
            "SAFETY VIOLATION: Target folder is missing non-empty 'reelix_test_id' property. "
            "Refusing to operate on unverified resources."
        )

    # Resolve real root folder ID and validate parentage exclusively against resolved actual root ID
    actual_root_id = resolve_root_folder_id(service)
    folder_parents = folder.get("parents", [])
    if actual_root_id not in folder_parents:
        raise ValueError(
            f"SAFETY VIOLATION: Test folder '{folder_name}' is not located directly in Drive root "
            f"(resolved actual root ID: '{actual_root_id}'). Observed parents: {folder_parents}."
        )

    # Reject if nested inside the production vault
    if clean_vault in folder_parents:
        raise ValueError(
            f"SAFETY VIOLATION: Test folder '{folder_name}' is nested inside production vault {clean_vault}."
        )

    logger.info("  ✓ Folder validated: '%s' (%s) directly under Drive root '%s' [test_id=%s]", folder_name, clean_folder, actual_root_id, folder_test_id)

    # 2. Validate File
    try:
        file_obj = service.files().get(
            fileId=clean_file,
            fields="id, name, mimeType, parents, trashed, properties",
        ).execute()
    except HttpError as e:
        logger.error("Failed to access file %s (HTTP %d): %s", clean_file, e.resp.status, e.reason)
        raise

    if file_obj.get("trashed", False):
        raise ValueError(f"Target file {clean_file} is trashed.")

    file_name = file_obj.get("name", "")
    if file_name != EXPECTED_FILE_NAME:
        raise ValueError(
            f"SAFETY VIOLATION: Expected target file name '{EXPECTED_FILE_NAME}', but found '{file_name}'."
        )

    file_mime = file_obj.get("mimeType", "")
    if file_mime != EXPECTED_FILE_MIME:
        raise ValueError(
            f"SAFETY VIOLATION: Expected target file mimeType '{EXPECTED_FILE_MIME}', but found '{file_mime}'."
        )

    file_parents = file_obj.get("parents", [])
    if clean_folder not in file_parents:
        raise ValueError(
            f"SAFETY VIOLATION: Supplied file_id {clean_file} does not belong to supplied folder_id {clean_folder}. "
            f"File parents: {file_parents}."
        )

    # Validate File properties and match with folder_test_id
    file_props = file_obj.get("properties") or {}
    file_test_id = (file_props.get("reelix_test_id") or "").strip()
    if not file_test_id:
        raise ValueError(
            "SAFETY VIOLATION: Target file is missing non-empty 'reelix_test_id' property. "
            "Refusing to operate on unverified resources."
        )

    if file_test_id != folder_test_id:
        raise ValueError(
            f"SAFETY VIOLATION: Target file 'reelix_test_id' ({file_test_id}) does not match "
            f"folder 'reelix_test_id' ({folder_test_id}). Refusing to operate on mismatched test resources."
        )

    logger.info("  ✓ File validated: '%s' (%s) inside folder %s [test_id=%s]", file_name, clean_file, clean_folder, file_test_id)

    # 3. Validate Folder Contents across ALL pages: Folder must contain ONLY the expected probe file
    children = list_all_children(service, clean_folder)
    if len(children) != 1 or children[0].get("id") != clean_file:
        child_names = [f"{c.get('name')} ({c.get('id')})" for c in children]
        raise ValueError(
            f"SAFETY VIOLATION: Test folder must contain ONLY '{EXPECTED_FILE_NAME}' ({clean_file}). "
            f"Found {len(children)} item(s) across all pages: {child_names}. Verification aborted to avoid operating on unexpected data."
        )

    logger.info("  ✓ Folder contents validated: Exactly 1 expected file present across all pages.")
    return folder, file_obj


def cleanup_validated_test_targets(service, folder_id: str, file_id: str) -> None:
    """Performs restricted, child-first cleanup on strictly validated targets.

    1. Checks folder contents across all pages to ensure no unexpected files have appeared.
    2. Deletes only the exact probe file.
    3. If probe file deletion fails, folder deletion is NOT attempted.
    4. Deletes only the exact test folder after file deletion succeeds.
    5. Raises an exception if any deletion step fails (never swallows errors).
    """
    from googleapiclient.errors import HttpError

    logger.info("------------------------------------------------------------------")
    logger.info("Executing restricted cleanup on validated test targets...")

    # Step 1: Ensure no unexpected files exist across all pages before deleting
    children = list_all_children(service, folder_id)
    unexpected = [c for c in children if c.get("id") != file_id]
    if unexpected:
        unexp_desc = [f"{u.get('name')} ({u.get('id')})" for u in unexpected]
        raise RuntimeError(
            f"Cleanup aborted: Unexpected files detected in test folder across pages: {unexp_desc}. "
            "Refusing to delete folder to avoid deleting unverified files."
        )

    # Step 2: Delete ONLY the exact probe file
    probe_in_folder = any(c.get("id") == file_id for c in children)
    if probe_in_folder:
        try:
            service.files().delete(fileId=file_id).execute()
            logger.info("  ✓ Deleted validated probe file %s [HTTP 200]", file_id)
        except HttpError as e:
            logger.error("  ✗ Failed to delete probe file %s (HTTP %d): %s", file_id, e.resp.status, e.reason)
            raise RuntimeError(f"Cleanup failure: could not delete probe file {file_id}: {e.reason}") from e
    else:
        logger.info("  - Probe file %s was already absent.", file_id)

    # Step 3: Delete ONLY the exact test folder (strictly after file is confirmed deleted)
    try:
        service.files().delete(fileId=folder_id).execute()
        logger.info("  ✓ Deleted validated test folder %s [HTTP 200]", folder_id)
    except HttpError as e:
        logger.error("  ✗ Failed to delete test folder %s (HTTP %d): %s", folder_id, e.resp.status, e.reason)
        raise RuntimeError(f"Cleanup failure: could not delete test folder {folder_id}: {e.reason}") from e

    logger.info("  ✓ Restricted cleanup completed successfully.")
    logger.info("------------------------------------------------------------------")


def run_cross_client_verification(
    service,
    folder_id: str,
    file_id: str,
    forbidden_vault_id: str,
    skip_cleanup: bool = False,
) -> bool:
    """Executes the 5 verification stages under drive.file scope with strict target validation."""
    from googleapiclient.errors import HttpError
    from googleapiclient.http import MediaIoBaseDownload, MediaIoBaseUpload

    clean_vault = (forbidden_vault_id or "").strip()
    if not clean_vault:
        raise ValueError(
            "SAFETY VIOLATION: Production vault ID (GDRIVE_FOLDER_ID) is missing or blank. "
            "Verification strictly requires GDRIVE_FOLDER_ID to enforce production-vault protection."
        )

    logger.info("==================================================================")
    logger.info("Starting Controlled Cross-Client Google Drive Access Verification")
    logger.info("Folder ID: %s", folder_id)
    logger.info("File ID:   %s", file_id)
    logger.info("==================================================================")

    # Pre-flight validation OUTSIDE the cleanup-protected block.
    # If this fails, targets_validated remains False and cleanup is NEVER executed.
    targets_validated = False
    folder, file_obj = validate_test_targets(service, folder_id, file_id, clean_vault)
    targets_validated = True

    verification_succeeded = False
    cleanup_error: Optional[Exception] = None

    try:
        # Stage 1: Folder Access Verification
        logger.info("Stage 1: Verifying folder access by Worker Client B...")
        logger.info("  ✓ Folder access confirmed [HTTP 200]: name='%s', id=%s", folder["name"], folder["id"])

        # Stage 2: File Listing Verification (Exhaustive pagination)
        logger.info("Stage 2: Listing files in folder by Worker Client B (with pagination)...")
        files = list_all_children(service, folder_id)
        logger.info("  ✓ Discovered %d file(s) across all pages [HTTP 200]", len(files))
        matching_file = next((f for f in files if f.get("id") == file_id), None)
        if not matching_file:
            raise FileNotFoundError(f"File {file_id} not visible in folder file list. Cross-client listing boundary active.")
        logger.info("  ✓ File '%s' (%s) visible in listing [HTTP 200]", matching_file.get("name"), file_id)

        # Stage 3: Metadata Verification
        logger.info("Stage 3: Retrieving file metadata by Worker Client B...")
        meta = service.files().get(
            fileId=file_id,
            fields="id, name, mimeType, size, modifiedTime, properties",
        ).execute()
        logger.info(
            "  ✓ Metadata retrieved [HTTP 200]: name='%s', mimeType='%s', size=%s bytes, properties=%s",
            meta.get("name"), meta.get("mimeType"), meta.get("size", "unknown"), meta.get("properties", {}),
        )

        # Stage 4: File Download Verification
        logger.info("Stage 4: Downloading file content by Worker Client B...")
        req = service.files().get_media(fileId=file_id)
        fh = io.BytesIO()
        downloader = MediaIoBaseDownload(fh, req)
        done = False
        while not done:
            _, done = downloader.next_chunk()

        downloaded_bytes = fh.getvalue()
        payload = json.loads(downloaded_bytes.decode("utf-8"))
        logger.info("  ✓ Downloaded file payload [HTTP 200]: %s", json.dumps(payload))

        if payload.get("version") != 1:
            raise ValueError(f"Expected payload version 1 from Android Client A, received: {payload.get('version')}")

        # Stage 5: Content Update Verification
        logger.info("Stage 5: Updating file content by Worker Client B...")
        payload["version"] = 2
        payload["updated_by"] = "worker_client_b"
        payload["worker_timestamp"] = int(time.time() * 1000)

        updated_bytes = json.dumps(payload, indent=2).encode("utf-8")
        media_body = MediaIoBaseUpload(io.BytesIO(updated_bytes), mimetype="application/json")

        service.files().update(
            fileId=file_id,
            media_body=media_body,
            fields="id, modifiedTime, version",
        ).execute()
        logger.info("  ✓ Updated file payload in Drive [HTTP 200]")

        # Confirm update persisted via re-download
        req2 = service.files().get_media(fileId=file_id)
        fh2 = io.BytesIO()
        downloader2 = MediaIoBaseDownload(fh2, req2)
        done2 = False
        while not done2:
            _, done2 = downloader2.next_chunk()

        verified_payload = json.loads(fh2.getvalue().decode("utf-8"))
        if verified_payload.get("version") == 2 and verified_payload.get("updated_by") == "worker_client_b":
            logger.info("  ✓ Worker re-read confirmed update persisted: version=2, updated_by=worker_client_b [HTTP 200]")
            verification_succeeded = True
        else:
            raise ValueError(f"Content verification mismatch after worker update: {verified_payload}")

        logger.info("==================================================================")
        logger.info("ALL 5 VERIFICATION STAGES SUCCEEDED [HTTP 200]")
        logger.info("Cross-client drive.file read, list, download, and update CONFIRMED!")
        logger.info("==================================================================")

    except Exception as e:
        logger.error("Verification failed during execution: %s", e)
        raise

    finally:
        # Cleanup executes ONLY if targets were validated and skip_cleanup is False
        if targets_validated and not skip_cleanup:
            try:
                cleanup_validated_test_targets(service, folder_id, file_id)
            except Exception as ce:
                logger.error("Cleanup failure occurred: %s", ce)
                cleanup_error = ce
        elif skip_cleanup:
            logger.info("Cleanup skipped by request (--skip-cleanup). Test files remain in Drive for inspection.")

    # If verification succeeded but cleanup failed, propagate failure to ensure non-zero exit code
    if cleanup_error is not None:
        raise RuntimeError(f"Cross-client test cleanup failed: {cleanup_error}") from cleanup_error

    return verification_succeeded


def main():
    parser = argparse.ArgumentParser(description="Cross-Client Drive Verification Runner")
    parser.add_argument("--folder-id", required=True, help="Google Drive Folder ID created by Android Client A")
    parser.add_argument("--file-id", required=True, help="Google Drive File ID created by Android Client A")
    parser.add_argument("--skip-cleanup", action="store_true", help="Preserve test files for inspection")
    args = parser.parse_args()

    client_id = os.getenv("GDRIVE_CLIENT_ID")
    client_secret = os.getenv("GDRIVE_CLIENT_SECRET")
    refresh_token = os.getenv("GDRIVE_REFRESH_TOKEN")
    vault_id = os.getenv("GDRIVE_FOLDER_ID")

    if not client_id or not client_secret or not refresh_token:
        logger.error("Missing required Google Drive OAuth credentials in environment (GDRIVE_CLIENT_ID, GDRIVE_CLIENT_SECRET, GDRIVE_REFRESH_TOKEN).")
        sys.exit(1)

    if not vault_id or not vault_id.strip():
        logger.error("Missing required GDRIVE_FOLDER_ID in environment. Refusing to run without production vault safeguard.")
        sys.exit(1)

    try:
        service = get_authorized_service(client_id, client_secret, refresh_token)
        success = run_cross_client_verification(
            service=service,
            folder_id=args.folder_id,
            file_id=args.file_id,
            forbidden_vault_id=vault_id.strip(),
            skip_cleanup=args.skip_cleanup,
        )
        sys.exit(0 if success else 1)
    except Exception as e:
        logger.error("Verification execution aborted: %s", e)
        sys.exit(1)


if __name__ == "__main__":
    main()
