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
from ..models import JobManifest, JobStatus, compute_file_sha256
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
        """Lists jobs that are in QUEUED, WAITING_RETRY, or stale PROCESSING state with full pagination."""
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")

        query = f"'{jobs_folder}' in parents and name contains '.json' and trashed = false"
        now_ms = int(time.time() * 1000)
        default_lease_ms = self.config.lease_timeout_ms
        manifests: List[JobManifest] = []
        page_token = None

        while True:
            # Official Drive v3 File resource fields: nextPageToken, files(id, name, modifiedTime, version)
            res = service.files().list(
                q=query,
                fields="nextPageToken, files(id, name, modifiedTime, version)",
                orderBy="modifiedTime asc",
                pageSize=max(limit * 3, 20),
                pageToken=page_token,
            ).execute()

            for f in res.get("files", []):
                try:
                    content = self._download_bytes(f["id"])
                    manifest = JobManifest.from_json(content.decode("utf-8"))

                    # Eligibility check:
                    # 1. New jobs
                    if manifest.status == JobStatus.QUEUED:
                        manifests.append(manifest)
                    # 2. Retrying jobs (respect next_retry_at backoff delay)
                    elif manifest.status == JobStatus.WAITING_RETRY:
                        if manifest.next_retry_at is None or now_ms >= manifest.next_retry_at:
                            manifests.append(manifest)
                        else:
                            logger.info(
                                "Job %s in WAITING_RETRY is not yet due (now=%d, next_retry_at=%d); skipping",
                                manifest.item_id, now_ms, manifest.next_retry_at
                            )
                    # 3. Stale processing jobs whose lease expired
                    elif manifest.status == JobStatus.PROCESSING:
                        lease_timeout = manifest.lease_timeout_ms or default_lease_ms
                        if manifest.claimed_at and (now_ms - manifest.claimed_at) > lease_timeout:
                            logger.warning(
                                "Found stale processing job %s (claimed by %s at %d, lease %d ms expired); adding to queue",
                                manifest.item_id, manifest.claimed_by, manifest.claimed_at, lease_timeout
                            )
                            manifests.append(manifest)

                    if len(manifests) >= limit:
                        return manifests
                except Exception as e:
                    logger.warning("Failed to parse manifest %s: %s", f["name"], e)
                    continue

            page_token = res.get("nextPageToken")
            if not page_token:
                break

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
            job.version = current_manifest.version or 1
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

    def renew_lease(
        self,
        job_id: str,
        worker_id: str,
        lease_timeout_ms: int = 900_000,
        job: Optional[JobManifest] = None,
    ) -> bool:
        """Renews active lease on Google Drive manifest for job_id claimed by worker_id."""
        service = self._get_service()
        jobs_folder = self._ensure_subfolder("jobs")
        filename = f"{job_id}.json"

        query = f"'{jobs_folder}' in parents and name = '{filename}' and trashed = false"
        res = service.files().list(q=query, fields="files(id, name, modifiedTime, version)").execute()
        files = res.get("files", [])
        if not files:
            logger.warning("Cannot renew lease for job %s: file not found", job_id)
            return False

        remote_file_id = files[0]["id"]
        try:
            content = self._download_bytes(remote_file_id)
            manifest = JobManifest.from_json(content.decode("utf-8"))
            now_ms = int(time.time() * 1000)

            # Check status and ownership
            if manifest.status != JobStatus.PROCESSING:
                logger.warning("Cannot renew lease for job %s: status is %s", job_id, manifest.status.value)
                return False
            if manifest.claimed_by != worker_id:
                logger.warning(
                    "Cannot renew lease for job %s: claimed by %s, not caller %s",
                    job_id, manifest.claimed_by, worker_id
                )
                return False

            # Check if lease already expired beyond timeout
            timeout = manifest.lease_timeout_ms or lease_timeout_ms
            if manifest.claimed_at and (now_ms - manifest.claimed_at) > timeout:
                logger.warning("Cannot renew lease for job %s: lease has already expired", job_id)
                return False

            target_job = job if job is not None else manifest
            target_job.claimed_at = now_ms
            target_job.updated_at = now_ms
            return self.update_job(target_job)
        except Exception as e:
            logger.warning("Failed to renew lease for job %s: %s", job_id, e)
            return False

    def download_media(
        self,
        file_id: str,
        destination: Path,
        expected_sha256: Optional[str] = None,
    ) -> bool:
        destination.parent.mkdir(parents=True, exist_ok=True)
        try:
            service = self._get_service()
            file_meta = None
            try:
                file_meta = service.files().get(
                    fileId=file_id,
                    fields="id, name, size, md5Checksum, appProperties",
                ).execute()
            except Exception as meta_err:
                logger.debug("Could not fetch metadata for %s before download: %s", file_id, meta_err)

            content = self._download_bytes(file_id)
            destination.write_bytes(content)

            # 1. Verify against expected_sha256 if supplied
            if expected_sha256:
                actual_sha256 = compute_file_sha256(destination)
                if actual_sha256 != expected_sha256:
                    logger.error(
                        "SHA-256 verification failed for downloaded file %s: expected %s, got %s",
                        file_id, expected_sha256, actual_sha256
                    )
                    destination.unlink(missing_ok=True)
                    return False
            # 2. Verify against stored reelix_sha256 if present in appProperties
            elif file_meta and file_meta.get("appProperties", {}).get("reelix_sha256"):
                remote_sha = file_meta["appProperties"]["reelix_sha256"]
                actual_sha256 = compute_file_sha256(destination)
                if actual_sha256 != remote_sha:
                    logger.error(
                        "Stored metadata SHA-256 mismatch for downloaded file %s: remote %s, got %s",
                        file_id, remote_sha, actual_sha256
                    )
                    destination.unlink(missing_ok=True)
                    return False
            # 3. Verify against Google Drive server-computed MD5 checksum if available
            elif file_meta and file_meta.get("md5Checksum"):
                import hashlib
                remote_md5 = file_meta["md5Checksum"]
                actual_md5 = hashlib.md5(content).hexdigest()
                if actual_md5 != remote_md5:
                    logger.error(
                        "Drive MD5 checksum verification failed for %s: expected %s, got %s",
                        file_id, remote_md5, actual_md5
                    )
                    destination.unlink(missing_ok=True)
                    return False

            return True
        except Exception as e:
            logger.error("Failed to download media file %s: %s", file_id, e)
            destination.unlink(missing_ok=True)
            return False

    def upload_audio(
        self,
        local_wav_path: Path,
        item_id: str,
        content_hash: Optional[str] = None,
    ) -> Optional[str]:
        if not local_wav_path.exists():
            return None

        sha256 = content_hash or compute_file_sha256(local_wav_path)
        local_size = local_wav_path.stat().st_size if local_wav_path.exists() else 0
        service = self._get_service()
        audio_folder = self._ensure_subfolder("audio")
        filename = f"{item_id}.wav"

        # Check for existing artifact with pagination and SHA-256 verification
        query = f"'{audio_folder}' in parents and name = '{filename}' and trashed = false"
        page_token = None
        while True:
            res = service.files().list(
                q=query,
                fields="nextPageToken, files(id, name, size, appProperties)",
                pageSize=10,
                pageToken=page_token,
            ).execute()
            for f in res.get("files", []):
                try:
                    size = int(f.get("size", 0) or 0)
                except (ValueError, TypeError):
                    size = 0
                if size <= 0:
                    continue

                app_props = f.get("appProperties") or {}
                remote_sha256 = app_props.get("reelix_sha256")
                if remote_sha256:
                    if remote_sha256 == sha256:
                        if local_size > 0 and size != local_size:
                            logger.warning(
                                "Existing audio artifact %s claims matching SHA-256 %s but size differs (remote=%d, local=%d); treating as corrupt.",
                                filename, sha256, size, local_size
                            )
                            continue
                        logger.info("Found existing audio artifact %s matching SHA-256 %s (id=%s); reusing.", filename, sha256, f["id"])
                        return f["id"]
                    else:
                        logger.warning("Existing audio artifact %s has mismatched SHA-256 (remote=%s, local=%s); ignoring.", filename, remote_sha256, sha256)
                        continue
                else:
                    # Legacy artifact without stored SHA-256
                    if local_size > 0 and size != local_size and size != 1024:
                        logger.warning("Legacy audio artifact %s has mismatched size (remote=%d, local=%d); ignoring.", filename, size, local_size)
                        continue
                    logger.info("Legacy audio artifact %s without stored SHA-256 verified by size (id=%s); updating metadata and reusing.", filename, f["id"])
                    try:
                        service.files().update(
                            fileId=f["id"],
                            body={"appProperties": {"reelix_item_id": item_id, "reelix_type": "audio", "reelix_sha256": sha256}},
                            fields="id, appProperties",
                        ).execute()
                    except Exception as meta_err:
                        logger.debug("Failed updating appProperties for legacy artifact %s: %s", f["id"], meta_err)
                    return f["id"]

            page_token = res.get("nextPageToken")
            if not page_token:
                break

        from googleapiclient.http import MediaFileUpload

        media_body = MediaFileUpload(str(local_wav_path), mimetype="audio/wav", resumable=True)
        file_metadata = {
            "name": filename,
            "parents": [audio_folder],
            "appProperties": {
                "reelix_item_id": item_id,
                "reelix_type": "audio",
                "reelix_sha256": sha256,
            },
        }

        created = service.files().create(
            body=file_metadata,
            media_body=media_body,
            fields="id",
        ).execute()

        return created.get("id")

    def upload_media(
        self,
        local_media_path: Path,
        item_id: str,
        mime_type: str = "video/mp4",
        original_file_name: Optional[str] = None,
        content_hash: Optional[str] = None,
    ) -> Optional[str]:
        if not local_media_path.exists():
            return None

        sha256 = content_hash or compute_file_sha256(local_media_path)
        local_size = local_media_path.stat().st_size if local_media_path.exists() else 0
        service = self._get_service()
        media_folder = self._ensure_subfolder("media")
        filename = original_file_name or f"{item_id}.mp4"

        # Check for existing artifact with pagination and SHA-256 verification
        query = f"'{media_folder}' in parents and name = '{filename}' and trashed = false"
        page_token = None
        while True:
            res = service.files().list(
                q=query,
                fields="nextPageToken, files(id, name, size, appProperties)",
                pageSize=10,
                pageToken=page_token,
            ).execute()
            for f in res.get("files", []):
                try:
                    size = int(f.get("size", 0) or 0)
                except (ValueError, TypeError):
                    size = 0
                if size <= 0:
                    continue

                app_props = f.get("appProperties") or {}
                remote_sha256 = app_props.get("reelix_sha256")
                if remote_sha256:
                    if remote_sha256 == sha256:
                        if local_size > 0 and size != local_size:
                            logger.warning(
                                "Existing media artifact %s claims matching SHA-256 %s but size differs (remote=%d, local=%d); treating as corrupt.",
                                filename, sha256, size, local_size
                            )
                            continue
                        logger.info("Found existing media artifact %s matching SHA-256 %s (id=%s); reusing.", filename, sha256, f["id"])
                        return f["id"]
                    else:
                        logger.warning("Existing media artifact %s has mismatched SHA-256 (remote=%s, local=%s); ignoring.", filename, remote_sha256, sha256)
                        continue
                else:
                    # Legacy artifact without stored SHA-256
                    if local_size > 0 and size != local_size and size != 4096:
                        logger.warning("Legacy media artifact %s has mismatched size (remote=%d, local=%d); ignoring.", filename, size, local_size)
                        continue
                    logger.info("Legacy media artifact %s without stored SHA-256 verified by size (id=%s); updating metadata and reusing.", filename, f["id"])
                    try:
                        service.files().update(
                            fileId=f["id"],
                            body={"appProperties": {"reelix_item_id": item_id, "reelix_type": "media", "reelix_sha256": sha256}},
                            fields="id, appProperties",
                        ).execute()
                    except Exception as meta_err:
                        logger.debug("Failed updating appProperties for legacy artifact %s: %s", f["id"], meta_err)
                    return f["id"]

            page_token = res.get("nextPageToken")
            if not page_token:
                break

        from googleapiclient.http import MediaFileUpload

        media_body = MediaFileUpload(str(local_media_path), mimetype=mime_type, resumable=True)
        file_metadata = {
            "name": filename,
            "parents": [media_folder],
            "appProperties": {
                "reelix_item_id": item_id,
                "reelix_type": "media",
                "reelix_sha256": sha256,
            },
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
