"""Diagnostic script for inspecting Google Drive queue state and folder identity safely.

Strict Requirements:
1. READ-ONLY: Never creates, modifies, or deletes any files or folders.
2. ZERO SECRETS: Never prints full access tokens, refresh tokens, client secrets, or full sensitive IDs.
3. FINGERPRINTING: Emits 16-char SHA-256 fingerprints and masked strings (first 4...last 4).
4. FOLDER COMPARISON: Safely verifies whether Android's folder matches GitHub Actions GDRIVE_FOLDER_ID.
5. QUERY AUDIT: Compares query with and without `name contains '.json'` to prove tokenizer behaviour.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import sys
from typing import Any, Dict, List, Optional, Tuple

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("diagnose_drive_queue")

DRIVE_SCOPES = ["https://www.googleapis.com/auth/drive.file"]


def fingerprint(val: str) -> str:
    """Returns the first 16 characters of the SHA-256 hex digest."""
    if not val:
        return "empty"
    return hashlib.sha256(val.strip().encode("utf-8")).hexdigest()[:16]


def mask_id(val: str) -> str:
    """Masks an ID preserving only first 4 and last 4 characters."""
    clean = val.strip() if val else ""
    if not clean:
        return "<none>"
    if len(clean) <= 8:
        return "***"
    return f"{clean[:4]}...{clean[-4:]}"


def format_id(val: str) -> str:
    """Returns a safe formatted string with mask and cryptographic fingerprint."""
    if not val:
        return "<not set>"
    return f"{mask_id(val)} (SHA256: {fingerprint(val)})"


def get_authorized_service(
    client_id: Optional[str] = None,
    client_secret: Optional[str] = None,
    refresh_token: Optional[str] = None,
    sa_json: Optional[str] = None,
    sa_path: Optional[str] = None,
):
    """Initializes Google Drive v3 API client in read-only mode."""
    from googleapiclient.discovery import build

    if sa_json:
        from google.oauth2.service_account import Credentials
        info = json.loads(sa_json)
        creds = Credentials.from_service_account_info(info, scopes=DRIVE_SCOPES)
        return build("drive", "v3", credentials=creds, cache_discovery=False)
    elif sa_path:
        from google.oauth2.service_account import Credentials
        creds = Credentials.from_service_account_file(sa_path, scopes=DRIVE_SCOPES)
        return build("drive", "v3", credentials=creds, cache_discovery=False)
    elif client_id and client_secret and refresh_token:
        from google.auth.transport.requests import Request
        from google.oauth2.credentials import Credentials
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
    else:
        raise ValueError("No valid Google Drive credentials provided (OAuth or Service Account).")


def run_diagnostics(
    service,
    gdrive_folder_id: Optional[str],
    android_folder_id: Optional[str] = None,
    android_folder_hash: Optional[str] = None,
) -> Dict[str, Any]:
    """Executes safe, non-destructive Drive queue diagnostics."""
    report: Dict[str, Any] = {
        "status": "RUNNING",
        "gdrive_folder_id": format_id(gdrive_folder_id or ""),
        "android_folder_id": format_id(android_folder_id or ""),
        "android_folder_hash": android_folder_hash or "<not provided>",
        "folder_identity_match": None,
        "vault_folders_found": [],
        "jobs_subfolder_found": False,
        "jobs_subfolder_id": None,
        "total_files_in_jobs": 0,
        "files_with_contains_query": 0,
        "files_dropped_by_contains_query": 0,
        "manifests": [],
        "summary": {},
    }

    print("=" * 72)
    print(" REELIX GOOGLE DRIVE QUEUE & FOLDER IDENTITY DIAGNOSTIC REPORT")
    print("=" * 72)

    # 1. Compare folder identities if Android folder info provided
    print("\n--- 1. FOLDER IDENTITY VERIFICATION ---")
    print(f"GitHub Actions GDRIVE_FOLDER_ID: {format_id(gdrive_folder_id or '')}")
    if android_folder_id:
        print(f"Android Reported Folder ID:    {format_id(android_folder_id)}")
        is_match = (gdrive_folder_id or "").strip() == android_folder_id.strip()
        report["folder_identity_match"] = is_match
        if is_match:
            print(">>> RESULT: MATCH! Android folder ID matches GitHub GDRIVE_FOLDER_ID exactly.")
        else:
            print(">>> RESULT: MISMATCH! Android folder ID does NOT match GitHub GDRIVE_FOLDER_ID!")
    elif android_folder_hash:
        gha_hash = fingerprint(gdrive_folder_id or "")
        is_match = gha_hash == android_folder_hash.strip().lower()
        report["folder_identity_match"] = is_match
        print(f"Android Reported Hash:         {android_folder_hash}")
        print(f"GitHub Actions SHA-256:        {gha_hash}")
        if is_match:
            print(">>> RESULT: MATCH! Android SHA-256 hash matches GitHub Actions folder hash.")
        else:
            print(">>> RESULT: MISMATCH! Android SHA-256 hash does NOT match GitHub Actions folder hash!")
    else:
        print("Android folder ID/hash not provided for comparison (pass via --android-folder-id).")

    # 2. Check all Reelix_Vault folders accessible in Google Drive
    print("\n--- 2. GLOBAL VAULT DISCOVERY IN DRIVE ---")
    try:
        q_vaults = "name = 'Reelix_Vault' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        vaults_res = service.files().list(q=q_vaults, fields="files(id, name, parents)").execute()
        vaults = vaults_res.get("files", [])
        print(f"Total 'Reelix_Vault' folders found in Drive: {len(vaults)}")
        for v in vaults:
            vid = v["id"]
            parents = v.get("parents", [])
            report["vault_folders_found"].append({"id_formatted": format_id(vid), "parents": parents})
            is_active_target = (vid == (gdrive_folder_id or "").strip())
            marker = " [CURRENT GHA TARGET]" if is_active_target else ""
            print(f"  • Folder: {format_id(vid)} | Parents: {parents}{marker}")

        if len(vaults) > 1:
            print(">>> WARNING: Multiple Reelix_Vault folders exist in this Google Drive!")
            print("    This can cause Android and GitHub Actions to write/read from separate vaults.")
    except Exception as e:
        print(f"Error searching for Reelix_Vault folders: {e}")

    # 3. Inspect target root folder
    effective_root = (gdrive_folder_id or "").strip()
    if not effective_root:
        print("\n>>> ERROR: GDRIVE_FOLDER_ID is empty. Cannot inspect queue folder.")
        report["status"] = "ERROR_NO_FOLDER_ID"
        return report

    print("\n--- 3. TARGET ROOT FOLDER INSPECTION ---")
    try:
        root_meta = service.files().get(fileId=effective_root, fields="id, name, mimeType, trashed, parents").execute()
        print(f"Folder exists: YES")
        print(f"Folder Name:   {root_meta.get('name')}")
        print(f"MIME Type:     {root_meta.get('mimeType')}")
        print(f"Trashed:       {root_meta.get('trashed')}")
        print(f"Parents:       {root_meta.get('parents')}")
    except Exception as e:
        print(f">>> ERROR accessing target root folder {format_id(effective_root)}: {e}")
        report["status"] = "ERROR_ACCESSING_ROOT"
        return report

    # 4. Inspect subfolders inside target root folder
    print("\n--- 4. SUBFOLDERS IN ROOT ---")
    jobs_folder_id = None
    try:
        q_sub = f"'{effective_root}' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        sub_res = service.files().list(q=q_sub, fields="files(id, name)").execute()
        subfolders = sub_res.get("files", [])
        print(f"Subfolders found in root ({len(subfolders)}):")
        for sf in subfolders:
            print(f"  • {sf.get('name')}: {format_id(sf.get('id', ''))}")
            if sf.get("name") == "jobs":
                jobs_folder_id = sf.get("id")
        report["jobs_subfolder_found"] = bool(jobs_folder_id)
        report["jobs_subfolder_id"] = format_id(jobs_folder_id or "")
    except Exception as e:
        print(f"Error listing subfolders: {e}")

    if not jobs_folder_id:
        print("\n>>> RESULT: 'jobs' subfolder NOT FOUND in root folder.")
        print("    If Android uploaded items to another folder, they will not appear here.")
        report["status"] = "NO_JOBS_FOLDER"
        return report

    # 5. Inspect 'jobs' subfolder and compare query syntax
    print("\n--- 5. QUEUE QUERY AUDIT & MANIFEST INSPECTION ---")
    try:
        # Query A: All untrashed files in jobs folder
        q_all = f"'{jobs_folder_id}' in parents and trashed = false"
        all_res = service.files().list(q=q_all, fields="files(id, name, size, modifiedTime)").execute()
        all_files = all_res.get("files", [])
        report["total_files_in_jobs"] = len(all_files)

        # Query B: The query previously used by Python (name contains '.json')
        q_contains = f"'{jobs_folder_id}' in parents and name contains '.json' and trashed = false"
        contains_res = service.files().list(q=q_contains, fields="files(id, name)").execute()
        contains_files = contains_res.get("files", [])
        report["files_with_contains_query"] = len(contains_files)

        dropped = len(all_files) - len(contains_files)
        report["files_dropped_by_contains_query"] = max(0, dropped)

        print(f"Query A ('{mask_id(jobs_folder_id)}' in parents and trashed = false):        {len(all_files)} file(s)")
        print(f"Query B (including 'name contains \".json\"' tokenizer filter):             {len(contains_files)} file(s)")
        if dropped > 0:
            print(f">>> CRITICAL FINDING: 'name contains \".json\"' DROPPED {dropped} FILE(S) due to Drive API tokenization!")
        else:
            print("Query counts match.")

        # 6. Parse and classify manifests
        print(f"\nManifest Details in jobs/ ({len(all_files)} files):")
        status_counts: Dict[str, int] = {}
        for f in all_files:
            fname = f.get("name", "")
            fid = f.get("id", "")
            if not fname.endswith(".json"):
                print(f"  • Non-manifest file skipped: {fname} ({format_id(fid)})")
                continue

            try:
                content = service.files().get_media(fileId=fid).execute()
                text = content.decode("utf-8") if isinstance(content, bytes) else str(content)
                data = json.loads(text)
                status = data.get("status", "UNKNOWN")
                item_id = data.get("item_id", data.get("job_id", "unknown"))
                claimed_by = data.get("claimed_by", "<none>")
                retry_count = data.get("retry_count", 0)
                original_url = data.get("original_url", "<no url>")

                status_counts[status] = status_counts.get(status, 0) + 1
                report["manifests"].append({
                    "file_name": fname,
                    "item_id": item_id,
                    "status": status,
                    "claimed_by": claimed_by,
                    "retry_count": retry_count,
                    "original_url": original_url,
                })
                print(f"  • [{status:12}] item={item_id} | retries={retry_count} | claimed_by={claimed_by} | url={original_url}")
            except Exception as parse_err:
                status_counts["PARSE_ERROR"] = status_counts.get("PARSE_ERROR", 0) + 1
                print(f"  • [PARSE_ERROR] file={fname} ({format_id(fid)}): {parse_err}")

        report["summary"] = status_counts
        print(f"\nManifest Status Summary:")
        for s, count in status_counts.items():
            print(f"  - {s}: {count}")

        queued_eligible = status_counts.get("QUEUED", 0) + status_counts.get("WAITING_RETRY", 0)
        print(f"\nTotal Pending Jobs Awaiting Worker: {queued_eligible}")

    except Exception as e:
        print(f"Error inspecting jobs subfolder: {e}")
        report["status"] = "ERROR_INSPECTING_JOBS"
        return report

    print("\n" + "=" * 72)
    print(" DIAGNOSTIC COMPLETE")
    print("=" * 72)
    report["status"] = "COMPLETED"
    return report


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Reelix Google Drive Queue Diagnostic Tool")
    parser.add_argument(
        "--android-folder-id",
        type=str,
        default=os.getenv("ANDROID_FOLDER_ID"),
        help="Google Drive Folder ID configured in Android app to verify against GitHub Actions GDRIVE_FOLDER_ID",
    )
    parser.add_argument(
        "--android-folder-hash",
        type=str,
        default=os.getenv("ANDROID_FOLDER_HASH"),
        help="SHA-256 hash (16 or 64 hex characters) of Android folder ID to compare without providing raw ID",
    )
    parser.add_argument(
        "--folder-id",
        type=str,
        default=os.getenv("GDRIVE_FOLDER_ID"),
        help="Target Google Drive folder ID to inspect (defaults to GDRIVE_FOLDER_ID env)",
    )
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    client_id = os.getenv("GDRIVE_CLIENT_ID")
    client_secret = os.getenv("GDRIVE_CLIENT_SECRET")
    refresh_token = os.getenv("GDRIVE_REFRESH_TOKEN")
    sa_json = os.getenv("GDRIVE_SERVICE_ACCOUNT_JSON")
    sa_path = os.getenv("GDRIVE_SERVICE_ACCOUNT_PATH")

    try:
        service = get_authorized_service(
            client_id=client_id,
            client_secret=client_secret,
            refresh_token=refresh_token,
            sa_json=sa_json,
            sa_path=sa_path,
        )
    except Exception as e:
        logger.error("Failed to initialize Google Drive service: %s", e)
        return 1

    report = run_diagnostics(
        service=service,
        gdrive_folder_id=args.folder_id,
        android_folder_id=args.android_folder_id,
        android_folder_hash=args.android_folder_hash,
    )

    return 0 if report.get("status") == "COMPLETED" else 1


if __name__ == "__main__":
    sys.exit(main())
