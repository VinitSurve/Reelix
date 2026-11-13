"""Local mock storage client for offline testing and verification."""

from __future__ import annotations

import json
import shutil
import time
from pathlib import Path
from typing import Dict, List, Optional

from ..models import (
    JobManifest,
    JobStatus,
    TransitionRecord,
    TransitionType,
    compute_file_sha256,
    fold_transitions,
)
from . import StorageClient


class MockStorageClient(StorageClient):
    """In-memory and local directory storage client for deterministic tests."""

    def __init__(self, root_dir: Optional[Path] = None, default_lease_ms: int = 900_000):
        self.root_dir = root_dir or Path("/tmp/reelix_mock_storage")
        self.jobs_dir = self.root_dir / "jobs"
        self.media_dir = self.root_dir / "media"
        self.audio_dir = self.root_dir / "audio"
        self.default_lease_ms = default_lease_ms

        self.jobs_dir.mkdir(parents=True, exist_ok=True)
        self.media_dir.mkdir(parents=True, exist_ok=True)
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        self.transitions_dir = self.root_dir / "transitions"
        self.transitions_dir.mkdir(parents=True, exist_ok=True)
        self.intents_dir = self.root_dir / "transition_intents"
        self.intents_dir.mkdir(parents=True, exist_ok=True)
        self.file_ids_dir = self.root_dir / "file_ids"
        self.file_ids_dir.mkdir(parents=True, exist_ok=True)

        self._files: Dict[str, Path] = {}
        self.etags: Dict[str, str] = {}
        self._etag_counter = 0
        self.simulated_listing_delay: Dict[str, int] = {}
        self.simulated_upload_conflicts: Dict[str, bool] = {}
        self.fault_crash_after_id_generation: bool = False
        self.fault_crash_after_intent_persistence: bool = False
        self.fault_crash_after_drive_creation_before_response: bool = False

    def register_media(self, file_id: str, path: Path):
        self._files[file_id] = path

    def _next_etag(self, item_id: str) -> str:
        self._etag_counter += 1
        return f"mock_etag_{item_id}_{self._etag_counter}"

    def list_queued_jobs(self, limit: int = 5) -> List[JobManifest]:
        now_ms = int(time.time() * 1000)
        results: List[JobManifest] = []
        for file in sorted(self.jobs_dir.glob("*.json")):
            try:
                manifest = JobManifest.from_json(file.read_text(encoding="utf-8"))
                manifest.etag = self.etags.get(manifest.item_id, "mock_etag_init")

                if manifest.status == JobStatus.QUEUED:
                    results.append(manifest)
                elif manifest.status == JobStatus.WAITING_RETRY:
                    if manifest.next_retry_at is None or now_ms >= manifest.next_retry_at:
                        results.append(manifest)
                elif manifest.status == JobStatus.PROCESSING:
                    lease = manifest.lease_timeout_ms or self.default_lease_ms
                    if manifest.claimed_at and (now_ms - manifest.claimed_at) > lease:
                        results.append(manifest)

                if len(results) >= limit:
                    break
            except Exception:
                continue
        return results

    def get_job(self, item_id: str) -> Optional[JobManifest]:
        job_file = self.jobs_dir / f"{item_id}.json"
        if not job_file.exists():
            return None
        manifest = JobManifest.from_json(job_file.read_text(encoding="utf-8"))
        manifest.etag = self.etags.get(item_id, "mock_etag_init")
        return manifest

    def claim_job(self, job: JobManifest, runner_id: Optional[str] = None) -> bool:
        actual_runner = runner_id or job.claimed_by or "mock-runner"
        existing = self.get_job(job.item_id)
        if existing:
            if job.etag and existing.etag and job.etag != existing.etag:
                return False
            if existing.generation > job.generation:
                return False
            if existing.status == JobStatus.PROCESSING:
                now_ms = int(time.time() * 1000)
                lease = existing.lease_timeout_ms or self.default_lease_ms
                if existing.claimed_at and (now_ms - existing.claimed_at) < lease:
                    return False
            elif existing.status in (JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.PARTIAL):
                return False
            job.generation = existing.generation
            job.version = existing.version or 1
        else:
            job.version = 1

        now_ms = int(time.time() * 1000)
        job.status = JobStatus.PROCESSING
        job.claimed_by = actual_runner
        job.claimed_at = now_ms
        job.updated_at = now_ms

        new_etag = self._next_etag(job.item_id)
        self.etags[job.item_id] = new_etag
        job.etag = new_etag

        job_file = self.jobs_dir / f"{job.item_id}.json"
        job_file.write_text(job.to_json(), encoding="utf-8")

        # Read-after-write verification
        verified = JobManifest.from_json(job_file.read_text(encoding="utf-8"))
        if verified.claimed_by != actual_runner:
            return False

        return True

    def renew_lease(
        self,
        job_id: str,
        worker_id: str,
        lease_timeout_ms: int = 900_000,
        job: Optional[JobManifest] = None,
    ) -> bool:
        """Heartbeat renewal of active lease on mock manifest."""
        existing = self.get_job(job_id)
        if not existing:
            return False
        if existing.status != JobStatus.PROCESSING:
            return False
        if existing.claimed_by != worker_id:
            return False
        if job is not None and existing.generation != job.generation:
            return False
        now_ms = int(time.time() * 1000)
        lease = existing.lease_timeout_ms or self.default_lease_ms
        if existing.claimed_at and (now_ms - existing.claimed_at) > lease:
            return False  # Already expired and lost

        target_job = job if job is not None else existing
        target_job.claimed_at = now_ms
        target_job.updated_at = now_ms
        return self.update_job(target_job)

    def update_job(self, job: JobManifest) -> bool:
        existing = self.get_job(job.item_id)
        if existing:
            # ETag conditional write check (simulate HTTP 412 Precondition Failed)
            if job.etag and existing.etag and job.etag != existing.etag:
                return False
            # Concurrency check: Generation ordering (stale worker protection)
            if existing.generation > job.generation:
                return False
            if existing.claimed_by and job.claimed_by and existing.claimed_by != job.claimed_by:
                return False
            if existing.status in (JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.PARTIAL) and job.status == JobStatus.PROCESSING:
                return False
            if existing.generation == job.generation:
                if (existing.version or 1) > (job.version or 1):
                    return False
                job.version = (existing.version or 1) + 1
            else:
                job.version = 1
        else:
            job.version = 1

        new_etag = self._next_etag(job.item_id)
        self.etags[job.item_id] = new_etag
        job.etag = new_etag

        job_file = self.jobs_dir / f"{job.item_id}.json"
        job_file.write_text(job.to_json(), encoding="utf-8")
        return True

    def download_media(
        self,
        file_id: str,
        destination: Path,
        expected_sha256: Optional[str] = None,
    ) -> bool:
        src = self._files.get(file_id)
        if not src or not src.exists():
            media_candidate = self.media_dir / file_id
            if media_candidate.exists():
                src = media_candidate
            else:
                return False
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, destination)
        if expected_sha256:
            actual = compute_file_sha256(destination)
            if actual != expected_sha256:
                destination.unlink(missing_ok=True)
                return False
        return True

    def upload_audio(
        self,
        local_wav_path: Path,
        item_id: str,
        content_hash: Optional[str] = None,
        generation: int = 1,
    ) -> Optional[str]:
        if not local_wav_path.exists():
            return None
        sha256 = content_hash or compute_file_sha256(local_wav_path)
        file_id = f"audio_{item_id}_gen{generation}.wav" if generation > 1 else f"audio_{item_id}.wav"
        dest = self.audio_dir / file_id
        # Idempotent deduplication: if destination artifact already exists with matching SHA-256 and generation, reuse it
        if dest.exists() and dest.stat().st_size > 0:
            existing_hash = compute_file_sha256(dest)
            if existing_hash == sha256:
                self._files[file_id] = dest
                return file_id

        shutil.copy2(local_wav_path, dest)
        self._files[file_id] = dest
        return file_id

    def upload_media(
        self,
        local_media_path: Path,
        item_id: str,
        mime_type: str = "video/mp4",
        original_file_name: Optional[str] = None,
        content_hash: Optional[str] = None,
        generation: int = 1,
    ) -> Optional[str]:
        if not local_media_path.exists():
            return None
        sha256 = content_hash or compute_file_sha256(local_media_path)
        file_id = f"media_{item_id}_gen{generation}.mp4" if generation > 1 else f"media_{item_id}.mp4"
        dest = self.media_dir / file_id
        # Idempotent deduplication: if destination artifact already exists with matching SHA-256 and generation, reuse it
        if dest.exists() and dest.stat().st_size > 0:
            existing_hash = compute_file_sha256(dest)
            if existing_hash == sha256:
                self._files[file_id] = dest
                return file_id

        shutil.copy2(local_media_path, dest)
        self._files[file_id] = dest
        return file_id

    def generate_drive_file_id(self) -> str:
        """Simulates obtaining an authorized pre-generated Google Drive file ID."""
        import uuid
        return f"mock_drive_id_{uuid.uuid4().hex[:16]}"

    def save_transition_intent(
        self,
        item_id: str,
        deterministic_transition_id: str,
        drive_file_id: str,
        payload_hash: str,
    ) -> bool:
        """Persists mapping between logical transition identity and Drive file ID on disk."""
        target_dir = self.intents_dir / item_id
        target_dir.mkdir(parents=True, exist_ok=True)
        payload = {
            "item_id": item_id,
            "deterministic_transition_id": deterministic_transition_id,
            "drive_file_id": drive_file_id,
            "payload_hash": payload_hash,
            "created_at": int(time.time() * 1000),
        }
        target = target_dir / f"{deterministic_transition_id}.json"
        target.write_text(json.dumps(payload), encoding="utf-8")
        return True

    def find_transition_intent(
        self,
        item_id: str,
        deterministic_transition_id: str,
    ) -> Optional[dict]:
        """Recovers persisted transition intent if a previous runner crashed before completing creation."""
        target = self.intents_dir / item_id / f"{deterministic_transition_id}.json"
        if target.exists():
            try:
                return json.loads(target.read_text(encoding="utf-8"))
            except Exception:
                return None
        return None

    def delete_transition_intent(
        self,
        item_id: str,
        deterministic_transition_id: str,
    ) -> bool:
        """Removes intent record after transition creation is verified."""
        target = self.intents_dir / item_id / f"{deterministic_transition_id}.json"
        if target.exists():
            target.unlink(missing_ok=True)
        return True

    def get_transition_by_file_id(self, file_id: str) -> Optional[TransitionRecord]:
        """Fetches transition record by its mock Drive file ID."""
        target = self.file_ids_dir / f"{file_id}.json"
        if target.exists():
            try:
                return TransitionRecord.from_json(target.read_text(encoding="utf-8"))
            except Exception:
                return None
        return None

    def append_transition(self, transition: TransitionRecord) -> bool:
        """Appends an immutable transition record to the item's transition directory."""
        item_dir = self.transitions_dir / transition.item_id
        item_dir.mkdir(parents=True, exist_ok=True)
        filename = f"{transition.deterministic_transition_id}.json"

        # Step 1: Search-before-create
        existing = self.find_transition(transition.item_id, transition.deterministic_transition_id)
        if existing is not None:
            if existing.matches_identity_and_payload(transition):
                self.delete_transition_intent(transition.item_id, transition.deterministic_transition_id)
                return True
            else:
                return False

        # Step 2: Intent recovery / ID generation
        intent = self.find_transition_intent(transition.item_id, transition.deterministic_transition_id)
        drive_file_id: Optional[str] = None
        if intent:
            candidate_id = intent.get("drive_file_id")
            if candidate_id:
                existing_by_id = self.get_transition_by_file_id(candidate_id)
                if existing_by_id is not None:
                    if existing_by_id.matches_identity_and_payload(transition):
                        self.delete_transition_intent(transition.item_id, transition.deterministic_transition_id)
                        return True
                    else:
                        return False
                drive_file_id = candidate_id

        if not drive_file_id:
            drive_file_id = self.generate_drive_file_id()
            if self.fault_crash_after_id_generation:
                raise RuntimeError("CRASH_AFTER_ID_GENERATION")
            self.save_transition_intent(
                transition.item_id,
                transition.deterministic_transition_id,
                drive_file_id,
                transition.payload_integrity_hash,
            )
            if self.fault_crash_after_intent_persistence:
                raise RuntimeError("CRASH_AFTER_INTENT_PERSISTENCE")

        # Step 3: Check for simulated 409 conflict
        if self.simulated_upload_conflicts.get(filename, False):
            # In simulated 409, file already exists on remote Drive with drive_file_id
            target = item_dir / filename
            if not target.exists():
                target.write_text(transition.to_json(), encoding="utf-8")
                (self.file_ids_dir / f"{drive_file_id}.json").write_text(transition.to_json(), encoding="utf-8")
            verified = self.get_transition_by_file_id(drive_file_id) or self.find_transition(transition.item_id, transition.deterministic_transition_id)
            if verified and verified.matches_identity_and_payload(transition):
                self.delete_transition_intent(transition.item_id, transition.deterministic_transition_id)
                return True
            return False

        # Step 4: Write transition and file ID link
        target = item_dir / filename
        transition_json = transition.to_json()
        target.write_text(transition_json, encoding="utf-8")
        (self.file_ids_dir / f"{drive_file_id}.json").write_text(transition_json, encoding="utf-8")

        if self.fault_crash_after_drive_creation_before_response:
            raise RuntimeError("CRASH_AFTER_DRIVE_CREATION_BEFORE_RESPONSE")

        self.delete_transition_intent(transition.item_id, transition.deterministic_transition_id)
        return True

    def list_transitions(self, item_id: str) -> List[TransitionRecord]:
        """Lists and deserializes all transitions for the given item."""
        item_dir = self.transitions_dir / item_id
        if not item_dir.exists():
            return []

        results: List[TransitionRecord] = []
        for file in sorted(item_dir.glob("*.json")):
            # Check simulated listing delay
            remaining_delay = self.simulated_listing_delay.get(file.name, 0)
            if remaining_delay > 0:
                self.simulated_listing_delay[file.name] = remaining_delay - 1
                continue

            try:
                t = TransitionRecord.from_json(file.read_text(encoding="utf-8"))
                results.append(t)
            except Exception:
                continue
        return results

    def find_transition(self, item_id: str, deterministic_transition_id: str) -> Optional[TransitionRecord]:
        """Searches specifically for an already-created transition record by its deterministic identity."""
        item_dir = self.transitions_dir / item_id
        target = item_dir / f"{deterministic_transition_id}.json"
        if target.exists():
            try:
                return TransitionRecord.from_json(target.read_text(encoding="utf-8"))
            except Exception:
                return None
        return None

    def rebuild_projection(self, item_id: str) -> Optional[JobManifest]:
        """Folds transitions for an item and updates jobs/{item_id}.json projection."""
        transitions = self.list_transitions(item_id)
        if not transitions:
            return self.get_job(item_id)
        base = self.get_job(item_id)
        folded, _ = fold_transitions(item_id, transitions, base_manifest=base)
        self.update_job(folded)
        return folded
