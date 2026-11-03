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

    @abc.abstractmethod
    def update_job(self, job: JobManifest) -> bool:
        """Persists the updated job manifest to cloud storage."""
        pass

    @abc.abstractmethod
    def download_media(self, file_id: str, destination: Path) -> bool:
        """Downloads a media file to local destination."""
        pass

    @abc.abstractmethod
    def upload_audio(self, local_wav_path: Path, item_id: str) -> Optional[str]:
        """Uploads an extracted audio WAV file and returns its cloud file ID."""
        pass

    @abc.abstractmethod
    def upload_media(
        self,
        local_media_path: Path,
        item_id: str,
        mime_type: str = "video/mp4",
        original_file_name: Optional[str] = None,
    ) -> Optional[str]:
        """Uploads acquired media file to cloud storage and returns its cloud file ID."""
        pass
