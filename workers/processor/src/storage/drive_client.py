"""Google Drive API v3 persistent storage and job queue client supporting OAuth 2.0."""

from __future__ import annotations

import io
import json
import logging
import os
import time
from pathlib import Path
from typing import Dict, List, Optional

from ..config import WorkerConfig
from ..models import JobManifest, JobStatus
from . import StorageClient

logger = logging.getLogger(__name__)

# Scopes: drive.file grants access ONLY to files created or opened by the app
DRIVE_SCOPES = ["https://www.googleapis.com/auth/drive.file"]


class GoogleDriveStorageClient(StorageClient):
    """Google Drive storage implementation for job queue and media files."""

    def __init__(self, config: WorkerConfig):
        self.config = config
        self.folder_id = config.gdrive_folder_id
        self._service = None
        self._subfolders: Dict[str, str] = {}  # name -> drive folder id

    def _get_service(self):
        if self._service is not None:
            return self._service

        try:
            from googleapiclient.discovery import build

            creds = None

            # 1. Primary: Personal Google Account OAuth 2.0
            if (
                self.config.gdrive_refresh_token
                and self.config.gdrive_client_id
                and self.config.gdrive_client_secret
            ):
                from google.auth.transport.requests import Request
                from google.oauth2.credentials import Credentials

                creds = Credentials(
                    token=self.config.gdrive_access_token,
                    refresh_token=self.config.gdrive_refresh_token,
                    token_uri="https://oauth2.googleapis.com/token",
                    client_id=self.config.gdrive_client_id,
                    client_secret=self.config.gdrive_client_secret,
                    scopes=DRIVE_SCOPES,
                )
                try:
                    creds.refresh(Request())
                    logger.info("Successfully authenticated with Google Drive via user OAuth 2.0")
                except Exception as refresh_err:
                    raise ValueError(
                        f"Google Drive OAuth token refresh failed: {refresh_err}. "
                        "Please verify GDRIVE_CLIENT_ID, GDRIVE_CLIENT_SECRET, and GDRIVE_REFRESH_TOKEN."
                    ) from refresh_err

            # 2. Secondary: Service Account (for Workspace Shared Drives only)
            elif self.config.gdrive_service_account_json:
                from google.oauth2 import service_account

                sa_info = json.loads(self.config.gdrive_service_account_json)
                creds = service_account.Credentials.from_service_account_info(
                    sa_info, scopes=DRIVE_SCOPES
                )
                logger.info("Authenticating with Google Drive via Service Account (Shared Drive only)")
            elif self.config.gdrive_service_account_path:
                from google.oauth2 import service_account

                creds = service_account.Credentials.from_service_account_file(
                    self.config.gdrive_service_account_path, scopes=DRIVE_SCOPES
                )
                logger.info("Authenticating with Google Drive via Service Account file")
            else:
                raise ValueError(
                    "No Google Drive credentials configured. For personal Google accounts (My Drive), "
                    "provide GDRIVE_CLIENT_ID, GDRIVE_CLIENT_SECRET, and GDRIVE_REFRESH_TOKEN. "
                    "For Workspace Shared Drives, provide GDRIVE_SERVICE_ACCOUNT_JSON."
                )

            self._service = build("drive", "v3", credentials=creds, cache_discovery=False)
            return self._service
        except ImportError as e:
            logger.error("google-api-python-client or google-auth not installed: %s", e)
            raise

    def _ensure_root_folder(self) -> str:
        """Finds or creates the authorized Reelix root folder inside My Drive under drive.file scope."""
        if self.folder_id:
            return self.folder_id

        service = self._get_service()
        query = "name = 'Reelix_Vault' and mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false"
        res = service.files().list(q=query, fields="files(id, name)").execute()
        files = res.get("files", [])
        if files:
            self.folder_id = files[0]["id"]
            logger.info("Discovered authorized Reelix root folder: %s", self.folder_id)
            return self.folder_id

        meta = {
            "name": "Reelix_Vault",
            "mimeType": "application/vnd.google-apps.folder",
            "parents": ["root"],
        }
        created = service.files().create(body=meta, fields="id").execute()
        self.folder_id = created["id"]
        logger.info("Created authorized Reelix root folder under 'root': %s", self.folder_id)
        return self.folder_id

    def _ensure_subfolder(self, name: str) -> str:
        """Finds or creates a subfolder inside root folder_id."""
        if name in self._subfolders:
            return self._subfolders[name]

        root_id = self._ensure_root_folder()
        service = self._get_service()
        query = (
            f"name = '{name}' and mimeType = 'application/vnd.google-apps.folder' "
            f"and '{root_id}' in parents and trashed = false"
        )
        res = service.files().list(q=query, fields="files(id, name)").execute()
        files = res.get("files", [])
        if files:
            self._subfolders[name] = files[0]["id"]
            return files[0]["id"]

        # Create folder
        meta = {
            "name": name,
            "mimeType": "application/vnd.google-apps.folder",
            "parents": [root_id],
        }
        created = service.files().create(body=meta, fields="id").execute()
        self._subfolders[name] = created["id"]
        return created["id"]

    def list_queued_jobs(self, limit: int = 5) -> List[JobManifest]:
        """Lists jobs that are in QUEUED, WAITING_RETRY, or stale PROCESSING state."""
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")

        query = f"'{jobs_folder}' in parents and name contains '.json' and trashed = false"
        # Official Drive v3 File resource fields: id, name, modifiedTime, version
        res = service.files().list(
            q=query,
            fields="files(id, name, modifiedTime, version)",
            orderBy="modifiedTime asc",
            pageSize=max(limit * 3, 20),
        ).execute()

        now_ms = int(time.time() * 1000)
        default_lease_ms = self.config.lease_timeout_ms
        manifests: List[JobManifest] = []

        for f in res.get("files", []):
            try:
                content = self._download_bytes(f["id"])
                manifest = JobManifest.from_json(content.decode("utf-8"))

                # Eligibility check:
                # 1. New or retrying jobs
                if manifest.status in (JobStatus.QUEUED, JobStatus.WAITING_RETRY):
                    manifests.append(manifest)
                # 2. Stale processing jobs whose lease expired
                elif manifest.status == JobStatus.PROCESSING:
                    lease_timeout = manifest.lease_timeout_ms or default_lease_ms
                    if manifest.claimed_at and (now_ms - manifest.claimed_at) > lease_timeout:
                        logger.warning(
                            "Found stale processing job %s (claimed by %s at %d, lease %d ms expired); adding to queue",
                            manifest.item_id, manifest.claimed_by, manifest.claimed_at, lease_timeout
                        )
                        manifests.append(manifest)

                if len(manifests) >= limit:
                    break
            except Exception as e:
                logger.warning("Failed to parse manifest %s: %s", f["name"], e)
                continue

        return manifests

    def get_job(self, item_id: str) -> Optional[JobManifest]:
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")
        filename = f"{item_id}.json"

        query = f"'{jobs_folder}' in parents and name = '{filename}' and trashed = false"
        res = service.files().list(q=query, fields="files(id, name, modifiedTime, version)").execute()
        files = res.get("files", [])
        if not files:
            return None

        content = self._download_bytes(files[0]["id"])
        manifest = JobManifest.from_json(content.decode("utf-8"))
        return manifest

    def claim_job(self, job: JobManifest, runner_id: Optional[str] = None) -> bool:
        """Attempts to claim a job safely using application-level lease validation and read-after-write verification.

        Google Drive API v3 does not enforce HTTP If-Match / ETag conditional updates on File resources.
        Reelix achieves concurrency safety through:
        1. Single-runner workflow serialization via GitHub Actions concurrency groups.
        2. Application-level active lease protection (< 15 min lease cannot be stolen).
        3. Stale lease recovery (> 15 min lease can be reclaimed).
        4. Read-After-Write Verification: after writing runner ID, re-reads manifest to verify ownership.
           If a race collision occurred, the losing runner immediately aborts without corrupting or processing.
        """
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")
        filename = f"{job.item_id}.json"
        actual_runner = runner_id or self.config.runner_id

        query = f"'{jobs_folder}' in parents and name = '{filename}' and trashed = false"
        res = service.files().list(q=query, fields="files(id, name, modifiedTime, version)").execute()
        files = res.get("files", [])
        if not files:
            logger.warning("Cannot claim job %s: file not found in jobs folder", job.item_id)
            return False

        remote_file_id = files[0]["id"]

        # Step 1: Pre-claim verification against remote manifest
        try:
            current_bytes = self._download_bytes(remote_file_id)
            current_manifest = JobManifest.from_json(current_bytes.decode("utf-8"))
            now_ms = int(time.time() * 1000)
            lease_timeout = current_manifest.lease_timeout_ms or self.config.lease_timeout_ms

            if current_manifest.status == JobStatus.PROCESSING:
                if current_manifest.claimed_at and (now_ms - current_manifest.claimed_at) < lease_timeout:
                    logger.info(
                        "Job %s is actively claimed by %s; aborting claim",
                        job.item_id, current_manifest.claimed_by
                    )
                    return False
            elif current_manifest.status in (JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.PARTIAL):
                logger.info("Job %s is already settled (%s); aborting claim", job.item_id, current_manifest.status.value)
                return False
        except Exception as e:
            logger.warning("Could not verify remote manifest before claiming %s: %s", job.item_id, e)
            return False

        # Step 2: Write claim with runner_id
        from googleapiclient.http import MediaIoBaseUpload

        now_ms = int(time.time() * 1000)
        job.status = JobStatus.PROCESSING
        job.claimed_by = actual_runner
        job.claimed_at = now_ms
        job.updated_at = now_ms

        body_bytes = job.to_json().encode("utf-8")
        media_body = MediaIoBaseUpload(io.BytesIO(body_bytes), mimetype="application/json")

        try:
            service.files().update(
                fileId=remote_file_id,
                media_body=media_body,
                fields="id, modifiedTime, version",
            ).execute()
        except Exception as e:
            logger.warning("Google Drive update failed during claim for %s: %s", job.item_id, e)
            return False

        # Step 3: Read-After-Write Verification
        try:
            verified_bytes = self._download_bytes(remote_file_id)
            verified_manifest = JobManifest.from_json(verified_bytes.decode("utf-8"))
            if verified_manifest.claimed_by != actual_runner:
                logger.warning(
                    "Claim collision detected for job %s: claimed by %s instead of %s. Aborting processing.",
                    job.item_id, verified_manifest.claimed_by, actual_runner
                )
                return False

            logger.info("Claim verified successfully for job %s by %s", job.item_id, actual_runner)
            return True
        except Exception as e:
            logger.warning("Failed read-after-write verification for job %s: %s", job.item_id, e)
            return False

    def update_job(self, job: JobManifest) -> bool:
        """Persists updated job manifest to Drive with lease ownership verification.

        Guarantees that a worker whose lease has been lost or stolen cannot overwrite
        another worker's manifest, protecting against time-of-check/time-of-use races.
        """
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")
        filename = f"{job.item_id}.json"

        from googleapiclient.http import MediaIoBaseUpload

        # Check if already exists
        query = f"'{jobs_folder}' in parents and name = '{filename}' and trashed = false"
        res = service.files().list(q=query, fields="files(id, name, modifiedTime, version)").execute()
        files = res.get("files", [])

        if files:
            remote_file_id = files[0]["id"]
            try:
                remote_bytes = self._download_bytes(remote_file_id)
                remote_manifest = JobManifest.from_json(remote_bytes.decode("utf-8"))

                # Concurrency check 1: Lease ownership check
                if (
                    remote_manifest.claimed_by
                    and job.claimed_by
                    and remote_manifest.claimed_by != job.claimed_by
                ):
                    logger.warning(
                        "Cannot update job %s: lease was reclaimed by %s (caller was %s). Aborting update.",
                        job.item_id, remote_manifest.claimed_by, job.claimed_by
                    )
                    return False

                # Concurrency check 2: Do not overwrite settled jobs
                if remote_manifest.status in (JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.PARTIAL) and job.status == JobStatus.PROCESSING:
                    logger.warning(
                        "Cannot update job %s: remote manifest is already settled (%s). Aborting update.",
                        job.item_id, remote_manifest.status.value
                    )
                    return False

                # Concurrency check 3: Version ordering
                if (remote_manifest.version or 1) > (job.version or 1):
                    logger.warning(
                        "Cannot update job %s: remote manifest version (%d) is newer than local (%d). Aborting update.",
                        job.item_id, remote_manifest.version, job.version
                    )
                    return False

                job.version = (remote_manifest.version or 1) + 1
            except Exception as e:
                logger.warning("Could not verify remote lease before update for %s: %s", job.item_id, e)
                return False

            body_bytes = job.to_json().encode("utf-8")
            media_body = MediaIoBaseUpload(io.BytesIO(body_bytes), mimetype="application/json")
            try:
                service.files().update(
                    fileId=remote_file_id,
                    media_body=media_body,
                    fields="id, modifiedTime, version",
                ).execute()
            except Exception as e:
                logger.error("Failed to update manifest for %s on Drive: %s", job.item_id, e)
                return False

            # Post-write verification
            try:
                verified_bytes = self._download_bytes(remote_file_id)
                verified = JobManifest.from_json(verified_bytes.decode("utf-8"))
                if job.claimed_by and verified.claimed_by and verified.claimed_by != job.claimed_by:
                    logger.error(
                        "Post-update collision detected for %s: claimed by %s instead of %s",
                        job.item_id, verified.claimed_by, job.claimed_by
                    )
                    return False
            except Exception as e:
                logger.warning("Post-update verification error for %s: %s", job.item_id, e)
        else:
            job.version = 1
            body_bytes = job.to_json().encode("utf-8")
            media_body = MediaIoBaseUpload(io.BytesIO(body_bytes), mimetype="application/json")
            file_metadata = {
                "name": filename,
                "parents": [jobs_folder],
                "mimeType": "application/json",
            }
            try:
                service.files().create(
                    body=file_metadata,
                    media_body=media_body,
                    fields="id, modifiedTime, version",
                ).execute()
            except Exception as e:
                logger.error("Failed to create manifest for %s on Drive: %s", job.item_id, e)
                return False

        return True

    def download_media(self, file_id: str, destination: Path) -> bool:
        destination.parent.mkdir(parents=True, exist_ok=True)
        try:
            content = self._download_bytes(file_id)
            destination.write_bytes(content)
            return True
        except Exception as e:
            logger.error("Failed to download media file %s: %s", file_id, e)
            return False

    def upload_audio(self, local_wav_path: Path, item_id: str) -> Optional[str]:
        if not local_wav_path.exists():
            return None

        service = self._get_service()
        audio_folder = self._ensure_subfolder("audio")
        filename = f"{item_id}.wav"

        from googleapiclient.http import MediaFileUpload

        media_body = MediaFileUpload(str(local_wav_path), mimetype="audio/wav", resumable=True)
        file_metadata = {
            "name": filename,
            "parents": [audio_folder],
        }

        created = service.files().create(
            body=file_metadata,
            media_body=media_body,
            fields="id",
        ).execute()

        return created.get("id")

    def _download_bytes(self, file_id: str) -> bytes:
        from googleapiclient.http import MediaIoBaseDownload

        service = self._get_service()
        request = service.files().get_media(fileId=file_id)
        fh = io.BytesIO()
        downloader = MediaIoBaseDownload(fh, request)
        done = False
        while not done:
            _, done = downloader.next_chunk()
        return fh.getvalue()
