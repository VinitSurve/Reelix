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

    def _ensure_subfolder(self, name: str) -> str:
        """Finds or creates a subfolder inside root folder_id."""
        if name in self._subfolders:
            return self._subfolders[name]

        service = self._get_service()
        query = (
            f"name = '{name}' and mimeType = 'application/vnd.google-apps.folder' "
            f"and '{self.folder_id}' in parents and trashed = false"
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
            "parents": [self.folder_id],
        }
        created = service.files().create(body=meta, fields="id").execute()
        self._subfolders[name] = created["id"]
        return created["id"]

    def list_queued_jobs(self, limit: int = 5) -> List[JobManifest]:
        """Lists jobs that are in QUEUED, WAITING_RETRY, or stale PROCESSING state."""
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")

        query = f"'{jobs_folder}' in parents and name contains '.json' and trashed = false"
        res = service.files().list(
            q=query,
            fields="files(id, name, etag, modifiedTime)",
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
                manifest.etag = f.get("etag")

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
        res = service.files().list(q=query, fields="files(id, name, etag)").execute()
        files = res.get("files", [])
        if not files:
            return None

        content = self._download_bytes(files[0]["id"])
        manifest = JobManifest.from_json(content.decode("utf-8"))
        manifest.etag = files[0].get("etag")
        return manifest

    def claim_job(self, job: JobManifest, expected_etag: Optional[str] = None) -> bool:
        """Attempts to claim a job atomically using ETag precondition and lease check."""
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")
        filename = f"{job.item_id}.json"

        query = f"'{jobs_folder}' in parents and name = '{filename}' and trashed = false"
        res = service.files().list(q=query, fields="files(id, name, etag)").execute()
        files = res.get("files", [])
        if not files:
            logger.warning("Cannot claim job %s: file not found in jobs folder", job.item_id)
            return False

        remote_file_id = files[0]["id"]
        remote_etag = files[0].get("etag")

        # Concurrency check 1: ETag mismatch
        if expected_etag and remote_etag and remote_etag != expected_etag:
            logger.warning(
                "Cannot claim job %s: ETag mismatch (expected %s, remote has %s)",
                job.item_id, expected_etag, remote_etag
            )
            return False

        # Concurrency check 2: Verify current remote status is eligible
        try:
            current_bytes = self._download_bytes(remote_file_id)
            current_manifest = JobManifest.from_json(current_bytes.decode("utf-8"))
            now_ms = int(time.time() * 1000)
            lease_timeout = current_manifest.lease_timeout_ms or self.config.lease_timeout_ms

            if current_manifest.status == JobStatus.PROCESSING:
                if current_manifest.claimed_at and (now_ms - current_manifest.claimed_at) < lease_timeout:
                    logger.info(
                        "Job %s was concurrently claimed by %s; aborting claim",
                        job.item_id, current_manifest.claimed_by
                    )
                    return False
            elif current_manifest.status == JobStatus.COMPLETED:
                logger.info("Job %s is already COMPLETED; aborting claim", job.item_id)
                return False
        except Exception as e:
            logger.warning("Could not verify remote manifest before claiming %s: %s", job.item_id, e)

        # Apply update with If-Match precondition
        from googleapiclient.http import MediaIoBaseUpload

        body_bytes = job.to_json().encode("utf-8")
        media_body = MediaIoBaseUpload(io.BytesIO(body_bytes), mimetype="application/json")

        try:
            req = service.files().update(
                fileId=remote_file_id,
                media_body=media_body,
                fields="id, etag, modifiedTime",
            )
            if expected_etag:
                req.headers["If-Match"] = expected_etag

            resp = req.execute()
            job.etag = resp.get("etag")
            logger.info("Claimed job %s successfully (new ETag %s)", job.item_id, job.etag)
            return True
        except Exception as e:
            logger.warning("Google Drive update failed during claim for %s: %s", job.item_id, e)
            return False

    def update_job(self, job: JobManifest) -> bool:
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")
        filename = f"{job.item_id}.json"
        body_bytes = job.to_json().encode("utf-8")

        from googleapiclient.http import MediaIoBaseUpload

        media_body = MediaIoBaseUpload(io.BytesIO(body_bytes), mimetype="application/json")

        # Check if already exists
        query = f"'{jobs_folder}' in parents and name = '{filename}' and trashed = false"
        res = service.files().list(q=query, fields="files(id, etag)").execute()
        files = res.get("files", [])

        if files:
            req = service.files().update(
                fileId=files[0]["id"],
                media_body=media_body,
                fields="id, etag, modifiedTime",
            )
            if job.etag:
                req.headers["If-Match"] = job.etag
            try:
                resp = req.execute()
                job.etag = resp.get("etag")
            except Exception as e:
                logger.warning("Conditional update failed for job %s, retrying without If-Match: %s", job.item_id, e)
                resp = service.files().update(
                    fileId=files[0]["id"],
                    media_body=media_body,
                    fields="id, etag, modifiedTime",
                ).execute()
                job.etag = resp.get("etag")
        else:
            file_metadata = {
                "name": filename,
                "parents": [jobs_folder],
                "mimeType": "application/json",
            }
            resp = service.files().create(
                body=file_metadata,
                media_body=media_body,
                fields="id, etag",
            ).execute()
            job.etag = resp.get("etag")

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
