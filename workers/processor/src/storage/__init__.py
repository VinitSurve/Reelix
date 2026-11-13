"""Storage interfaces and implementations for Reelix persistent cloud storage."""

from __future__ import annotations

import abc
from pathlib import Path
from typing import List, Optional

from ..models import JobManifest, TransitionRecord


class StorageClient(abc.ABC):
    """Abstract storage interface for persistent queue, immutable transitions, and media storage."""

    def append_transition(self, transition: TransitionRecord) -> bool:
        """Appends an immutable transition record to the item's transition log."""
        return True

    def generate_drive_file_id(self) -> str:
        """Obtains an authorized pre-generated Google Drive file ID."""
        import uuid
        return f"gen_id_{uuid.uuid4().hex}"

    def save_transition_intent(
        self,
        item_id: str,
        deterministic_transition_id: str,
        drive_file_id: str,
        payload_hash: str,
    ) -> bool:
        """Persists mapping between logical transition identity and Drive file ID across runner lifecycles."""
        return True

    def find_transition_intent(
        self,
        item_id: str,
        deterministic_transition_id: str,
    ) -> Optional[dict]:
        """Recovers persisted transition intent if a previous runner crashed before completing creation."""
        return None

    def find_all_transition_intents(
        self,
        item_id: str,
        deterministic_transition_id: str,
    ) -> List[dict]:
        """Discovers all persisted transition intent records for deterministic duplicate reconciliation."""
        single = self.find_transition_intent(item_id, deterministic_transition_id)
        return [single] if single else []

    def delete_transition_intent(
        self,
        item_id: str,
        deterministic_transition_id: str,
    ) -> bool:
        """Removes intent record after transition creation is verified."""
        return True

    def get_transition_by_file_id(self, file_id: str) -> Optional[TransitionRecord]:
        """Fetches transition record by its remote Drive file ID."""
        return None

    def list_transitions(self, item_id: str) -> List[TransitionRecord]:
        """Lists all immutable transition records for a specific item ID."""
        return []

    def find_transition(self, item_id: str, deterministic_transition_id: str) -> Optional[TransitionRecord]:
        """Finds an existing transition record by its deterministic identity if already present."""
        for t in self.list_transitions(item_id):
            if t.deterministic_transition_id == deterministic_transition_id:
                return t
        return None

    def rebuild_projection(self, item_id: str) -> Optional[JobManifest]:
        """Reconstructs and persists the materialized projection jobs/{item_id}.json from transitions."""
        return None

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
        attempt_epoch: int = 1,
    ) -> Optional[str]:
        """Uploads an extracted audio WAV file scoped by generation and epoch with hash deduplication."""
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
        attempt_epoch: int = 1,
    ) -> Optional[str]:
        """Uploads acquired media file scoped by generation and epoch with hash deduplication."""
        pass
