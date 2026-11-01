"""Google Drive API v3 persistent storage and job queue client."""

from __future__ import annotations

import io
import json
import logging
import os
from pathlib import Path
from typing import Dict, List, Optional

from ..config import WorkerConfig
from ..models import JobManifest, JobStatus
from . import StorageClient

logger = logging.getLogger(__name__)

# Scopes: drive.file gives access ONLY to files created or opened by the app, or shared with the service account
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
            from google.oauth2 import service_account
            from googleapiclient.discovery import build

            creds = None
            if self.config.gdrive_service_account_json:
                sa_info = json.loads(self.config.gdrive_service_account_json)
                creds = service_account.Credentials.from_service_account_info(
                    sa_info, scopes=DRIVE_SCOPES
                )
            elif self.config.gdrive_service_account_path:
                creds = service_account.Credentials.from_service_account_file(
                    self.config.gdrive_service_account_path, scopes=DRIVE_SCOPES
                )
            else:
                raise ValueError("No Google Drive service account credentials configured")

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
        """Lists jobs that are in QUEUED or WAITING_RETRY state."""
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")

        query = f"'{jobs_folder}' in parents and name contains '.json' and trashed = false"
        res = service.files().list(
            q=query,
            fields="files(id, name, modifiedTime)",
            orderBy="modifiedTime asc",
            pageSize=max(limit * 3, 20),
        ).execute()

        manifests: List[JobManifest] = []
        for f in res.get("files", []):
            try:
                content = self._download_bytes(f["id"])
                manifest = JobManifest.from_json(content.decode("utf-8"))
                if manifest.status in (JobStatus.QUEUED, JobStatus.WAITING_RETRY):
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
        res = service.files().list(q=query, fields="files(id, name)").execute()
        files = res.get("files", [])
        if not files:
            return None

        content = self._download_bytes(files[0]["id"])
        return JobManifest.from_json(content.decode("utf-8"))

    def update_job(self, job: JobManifest) -> bool:
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")
        filename = f"{job.item_id}.json"
        body_bytes = job.to_json().encode("utf-8")

        from googleapiclient.http import MediaIoBaseUpload

        media_body = MediaIoBaseUpload(io.BytesIO(body_bytes), mimetype="application/json")

        # Check if already exists
        query = f"'{jobs_folder}' in parents and name = '{filename}' and trashed = false"
        res = service.files().list(q=query, fields="files(id)").execute()
        files = res.get("files", [])

        if files:
            service.files().update(
                fileId=files[0]["id"],
                media_body=media_body,
            ).execute()
        else:
            file_metadata = {
                "name": filename,
                "parents": [jobs_folder],
                "mimeType": "application/json",
            }
            service.files().create(
                body=file_metadata,
                media_body=media_body,
                fields="id",
            ).execute()

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
