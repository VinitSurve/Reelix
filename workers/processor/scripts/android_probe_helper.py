"""Android Client A Probe Helper for Controlled Cross-Client Verification.

This helper performs Client A's operations:
1. create_probe: Creates 'Reelix_CrossClient_Test_<timestamp>' directly under Drive 'root' and 'manifest_probe.json' (version 1).
2. verify_probe: Reads 'manifest_probe.json' from the test folder and verifies 'version == 2' and 'updated_by == worker_client_b'.

Scope:
    https://www.googleapis.com/auth/drive.file

Safety & Credentials:
    - Access tokens are NEVER accepted via CLI arguments (--token).
    - Tokens are ingested exclusively via protected environment variables (ANDROID_ACCESS_TOKEN / REELIX_ANDROID_TOKEN)
      or secure interactive prompt (getpass) when run in a TTY.
    - Zero tokens or authorization headers are logged or output in errors.
    - Validates returned Drive IDs format and checks HTTP success codes.
"""

from __future__ import annotations

import argparse
import getpass
import io
import json
import logging
import os
import re
import sys
import time
import uuid
from typing import Tuple

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("android_probe_helper")

DRIVE_SCOPE = ["https://www.googleapis.com/auth/drive.file"]
SAFE_FOLDER_PREFIX = "Reelix_CrossClient_Test_"
PROBE_FILE_NAME = "manifest_probe.json"
DRIVE_ID_PATTERN = re.compile(r"^[a-zA-Z0-9_-]{15,100}$")


def get_access_token() -> str:
    """Securely resolves Android OAuth access token from environment or hidden prompt.

    Never accepts tokens via command-line arguments to prevent exposure in process table (ps)
    and shell history files.
    """
    # 1. Check protected environment variables
    token = os.getenv("ANDROID_ACCESS_TOKEN") or os.getenv("REELIX_ANDROID_TOKEN")
    if token and token.strip():
        return token.strip()

    # 2. Check interactive hidden prompt if in TTY
    if sys.stdin.isatty():
        try:
            prompt_token = getpass.getpass("Enter Android OAuth access token (hidden): ").strip()
            if prompt_token:
                return prompt_token
        except Exception:
            pass

    raise ValueError(
        "Android OAuth access token not found. "
        "Export it securely via 'export ANDROID_ACCESS_TOKEN=...' or run in an interactive terminal."
    )


def get_service(token: str):
    if not token or not token.strip():
        raise ValueError("OAuth access token must not be empty.")
    from google.oauth2.credentials import Credentials
    from googleapiclient.discovery import build

    creds = Credentials(token=token.strip(), scopes=DRIVE_SCOPE)
    return build("drive", "v3", credentials=creds, cache_discovery=False)


def validate_drive_id(identifier: str, kind: str) -> str:
    """Validates that a returned Google Drive ID conforms to expected format."""
    if not identifier or not isinstance(identifier, str):
        raise ValueError(f"Drive API returned empty or non-string {kind} ID.")
    clean_id = identifier.strip()
    if not DRIVE_ID_PATTERN.match(clean_id):
        raise ValueError(f"Drive API returned unexpected {kind} ID format (length={len(clean_id)}).")
    return clean_id


def create_probe(token: str) -> Tuple[str, str]:
    from googleapiclient.errors import HttpError
    from googleapiclient.http import MediaIoBaseUpload

    service = get_service(token)
    run_id = f"{int(time.time())}_{uuid.uuid4().hex[:6]}"
    folder_name = f"{SAFE_FOLDER_PREFIX}{run_id}"

    # 1. Create Folder directly under 'root'
    logger.info("Creating isolated test folder '%s' under Drive 'root'...", folder_name)
    folder_meta = {
        "name": folder_name,
        "mimeType": "application/vnd.google-apps.folder",
        "parents": ["root"],
        "properties": {"reelix_test_id": run_id, "reelix_type": "cross_client_test"},
    }

    try:
        folder_res = service.files().create(body=folder_meta, fields="id, name, mimeType, parents").execute()
    except HttpError as e:
        logger.error("Failed to create folder on Drive (HTTP %d): %s", e.resp.status, e.reason)
        raise RuntimeError(f"Drive API error creating test folder: HTTP {e.resp.status} - {e.reason}") from e
    except Exception as e:
        logger.error("Unexpected error creating folder: %s", type(e).__name__)
        raise

    if not folder_res or not isinstance(folder_res, dict):
        raise RuntimeError("Drive API returned empty or invalid response body for folder creation.")

    folder_id = validate_drive_id(folder_res.get("id", ""), "folder")
    logger.info("  ✓ Test folder created successfully: %s", folder_id)

    # 2. Create Probe File inside the newly created test folder
    logger.info("Uploading probe JSON '%s' inside folder %s...", PROBE_FILE_NAME, folder_id)
    payload = {
        "version": 1,
        "test_id": run_id,
        "created_by": "android_client_a",
        "timestamp": int(time.time() * 1000),
        "message": "Harmless controlled test probe payload",
    }
    file_bytes = json.dumps(payload, indent=2).encode("utf-8")
    file_meta = {
        "name": PROBE_FILE_NAME,
        "parents": [folder_id],
        "mimeType": "application/json",
        "properties": {"reelix_test_id": run_id},
    }
    media_body = MediaIoBaseUpload(io.BytesIO(file_bytes), mimetype="application/json")

    try:
        file_res = (
            service.files()
            .create(body=file_meta, media_body=media_body, fields="id, name, mimeType, parents")
            .execute()
        )
    except HttpError as e:
        logger.error("Failed to create probe file on Drive (HTTP %d): %s", e.resp.status, e.reason)
        raise RuntimeError(f"Drive API error creating probe file: HTTP {e.resp.status} - {e.reason}") from e
    except Exception as e:
        logger.error("Unexpected error creating probe file: %s", type(e).__name__)
        raise

    if not file_res or not isinstance(file_res, dict):
        raise RuntimeError("Drive API returned empty or invalid response body for file creation.")

    file_id = validate_drive_id(file_res.get("id", ""), "file")
    logger.info("  ✓ Probe file created successfully: %s", file_id)

    print("\n==================================================================")
    print("   Android Client A Probe Created Successfully")
    print("==================================================================")
    print(f"Folder Name:     {folder_name}")
    print(f"TEST_FOLDER_ID:  {folder_id}")
    print(f"TEST_FILE_ID:    {file_id}")
    print("==================================================================")
    print("Pass these IDs to the GitHub Actions workflow_dispatch inputs.")
    print("==================================================================\n")

    return folder_id, file_id


def verify_probe(token: str, folder_id: str, file_id: str) -> bool:
    from googleapiclient.errors import HttpError
    from googleapiclient.http import MediaIoBaseDownload

    clean_folder = validate_drive_id(folder_id, "folder")
    clean_file = validate_drive_id(file_id, "file")

    service = get_service(token)

    # 1. Retrieve file metadata and verify parents
    try:
        meta = service.files().get(fileId=clean_file, fields="id, name, mimeType, parents").execute()
    except HttpError as e:
        logger.error("Failed to retrieve file metadata (HTTP %d): %s", e.resp.status, e.reason)
        return False

    if not meta or not isinstance(meta, dict):
        logger.error("Empty response body when retrieving file metadata.")
        return False

    if clean_folder not in meta.get("parents", []):
        logger.error("File %s does not belong to folder %s", clean_file, clean_folder)
        return False

    # 2. Download and verify content
    try:
        req = service.files().get_media(fileId=clean_file)
        fh = io.BytesIO()
        downloader = MediaIoBaseDownload(fh, req)
        done = False
        while not done:
            _, done = downloader.next_chunk()
    except HttpError as e:
        logger.error("Failed to download probe file (HTTP %d): %s", e.resp.status, e.reason)
        return False

    body_bytes = fh.getvalue()
    if not body_bytes:
        logger.error("Downloaded probe file is empty.")
        return False

    try:
        data = json.loads(body_bytes.decode("utf-8"))
    except Exception as e:
        logger.error("Failed to parse probe file as JSON: %s", type(e).__name__)
        return False

    logger.info("Android Client A re-read probe content: %s", json.dumps(data))

    version = data.get("version")
    updated_by = data.get("updated_by")

    if version == 2 and updated_by == "worker_client_b":
        print("\n==================================================================")
        print("   SUCCESS! Cross-Client Modification Confirmed by Android Client A")
        print("==================================================================")
        print("Android Client A successfully re-read and verified the worker's modification:")
        print("  - Version: 2")
        print("  - Updated by: worker_client_b")
        print("Cross-client drive.file read/write interoperability is CONFIRMED.")
        print("==================================================================\n")
        return True
    else:
        logger.error("Verification failed: expected version 2 and updated_by 'worker_client_b', got: %s", data)
        return False


def main():
    parser = argparse.ArgumentParser(description="Android Client A Probe Helper (Secure Token Ingestion)")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # Create probe (no --token argument!)
    subparsers.add_parser(
        "create",
        help="Create probe folder and manifest_probe.json (reads token from ANDROID_ACCESS_TOKEN or prompt)",
    )

    # Verify probe (no --token argument!)
    verify_parser = subparsers.add_parser(
        "verify",
        help="Verify probe modification after worker update (reads token from ANDROID_ACCESS_TOKEN or prompt)",
    )
    verify_parser.add_argument("--folder-id", required=True, help="Test folder ID created by create")
    verify_parser.add_argument("--file-id", required=True, help="Test file ID created by create")

    args = parser.parse_args()

    try:
        token = get_access_token()
        if args.command == "create":
            create_probe(token)
        elif args.command == "verify":
            ok = verify_probe(token, args.folder_id, args.file_id)
            sys.exit(0 if ok else 1)
    except Exception as e:
        logger.error("Android probe operation failed: %s", e)
        sys.exit(1)


if __name__ == "__main__":
    main()
