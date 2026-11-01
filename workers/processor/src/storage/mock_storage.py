"""Local mock storage client for offline testing and verification."""

from __future__ import annotations

import json
import shutil
import time
from pathlib import Path
from typing import Dict, List, Optional

from ..models import JobManifest, JobStatus
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

        self._files: Dict[str, Path] = {}
        self.etags: Dict[str, str] = {}
        self._etag_counter = 0

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

                if manifest.status in (JobStatus.QUEUED, JobStatus.WAITING_RETRY):
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
            if existing.status == JobStatus.PROCESSING:
                now_ms = int(time.time() * 1000)
                lease = existing.lease_timeout_ms or self.default_lease_ms
                if existing.claimed_at and (now_ms - existing.claimed_at) < lease:
                    return False
            elif existing.status in (JobStatus.COMPLETED, JobStatus.FAILED):
                return False

        now_ms = int(time.time() * 1000)
        job.status = JobStatus.PROCESSING
        job.claimed_by = actual_runner
        job.claimed_at = now_ms
        job.updated_at = now_ms

        job_file = self.jobs_dir / f"{job.item_id}.json"
        job_file.write_text(job.to_json(), encoding="utf-8")

        # Read-after-write verification
        verified = JobManifest.from_json(job_file.read_text(encoding="utf-8"))
        if verified.claimed_by != actual_runner:
            return False

        return True

    def update_job(self, job: JobManifest) -> bool:
        existing = self.get_job(job.item_id)
        if (
            existing
            and existing.claimed_by
            and job.claimed_by
            and existing.claimed_by != job.claimed_by
        ):
            return False

        job_file = self.jobs_dir / f"{job.item_id}.json"
        job_file.write_text(job.to_json(), encoding="utf-8")
        return True

    def download_media(self, file_id: str, destination: Path) -> bool:
        src = self._files.get(file_id)
        if not src or not src.exists():
            media_candidate = self.media_dir / file_id
            if media_candidate.exists():
                src = media_candidate
            else:
                return False
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, destination)
        return True

    def upload_audio(self, local_wav_path: Path, item_id: str) -> Optional[str]:
        if not local_wav_path.exists():
            return None
        file_id = f"audio_{item_id}.wav"
        dest = self.audio_dir / file_id
        shutil.copy2(local_wav_path, dest)
        self._files[file_id] = dest
        return file_id
