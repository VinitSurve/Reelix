"""Storage interfaces and implementations for Reelix persistent cloud storage."""

from __future__ import annotations

import abc
from pathlib import Path
from typing import List, Optional

from ..models import JobManifest


class StorageClient(abc.ABC):
    """Abstract storage interface for persistent queue and media storage."""

    @abc.abstractmethod
    def list_queued_jobs(self, limit: int = 5) -> List[JobManifest]:
        """Lists pending or due jobs (including stale processing jobs)."""
        pass

    @abc.abstractmethod
    def get_job(self, item_id: str) -> Optional[JobManifest]:
        """Fetches the manifest for a specific item ID."""
        pass

    @abc.abstractmethod
    def claim_job(self, job: JobManifest, runner_id: Optional[str] = None) -> bool:
        """Attempts to claim a job safely with lease validation and read-after-write verification."""
        pass

    def renew_lease(
        self,
        job_id: str,
        worker_id: str,
        lease_timeout_ms: int = 900_000,
        job: Optional[JobManifest] = None,
    ) -> bool:
        """Heartbeat renewal for an active lease. Returns True if lease renewed, False if lost."""
        return True

    @abc.abstractmethod
    def update_job(self, job: JobManifest) -> bool:
        """Persists the updated job manifest to cloud storage."""
        pass

    @abc.abstractmethod
    def download_media(
        self,
        file_id: str,
        destination: Path,
        expected_sha256: Optional[str] = None,
    ) -> bool:
        """Downloads a media file to local destination with optional SHA-256 verification."""
        pass

    @abc.abstractmethod
    def upload_audio(
        self,
        local_wav_path: Path,
        item_id: str,
        content_hash: Optional[str] = None,
        generation: int = 1,
    ) -> Optional[str]:
        """Uploads an extracted audio WAV file with generation isolation and content hash deduplication."""
        pass

    @abc.abstractmethod
    def upload_media(
        self,
        local_media_path: Path,
        item_id: str,
        mime_type: str = "video/mp4",
        original_file_name: Optional[str] = None,
        content_hash: Optional[str] = None,
        generation: int = 1,
    ) -> Optional[str]:
        """Uploads acquired media file to cloud storage with generation isolation and content hash deduplication."""
        pass
