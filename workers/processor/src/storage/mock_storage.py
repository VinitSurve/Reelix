"""Local mock storage client for offline testing and verification."""

from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Dict, List, Optional

from ..models import JobManifest, JobStatus
from . import StorageClient


class MockStorageClient(StorageClient):
    """In-memory and local directory storage client for deterministic tests."""

    def __init__(self, root_dir: Optional[Path] = None):
        self.root_dir = root_dir or Path("/tmp/reelix_mock_storage")
        self.jobs_dir = self.root_dir / "jobs"
        self.media_dir = self.root_dir / "media"
        self.audio_dir = self.root_dir / "audio"

        self.jobs_dir.mkdir(parents=True, exist_ok=True)
        self.media_dir.mkdir(parents=True, exist_ok=True)
        self.audio_dir.mkdir(parents=True, exist_ok=True)

        self._files: Dict[str, Path] = {}

    def register_media(self, file_id: str, path: Path):
        self._files[file_id] = path

    def list_queued_jobs(self, limit: int = 5) -> List[JobManifest]:
        results: List[JobManifest] = []
        for file in sorted(self.jobs_dir.glob("*.json")):
            try:
                manifest = JobManifest.from_json(file.read_text(encoding="utf-8"))
                if manifest.status in (JobStatus.QUEUED, JobStatus.WAITING_RETRY):
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
        return JobManifest.from_json(job_file.read_text(encoding="utf-8"))

    def update_job(self, job: JobManifest) -> bool:
        job_file = self.jobs_dir / f"{job.item_id}.json"
        job_file.write_text(job.to_json(), encoding="utf-8")
        return True

    def download_media(self, file_id: str, destination: Path) -> bool:
        src = self._files.get(file_id)
        if not src or not src.exists():
            # Check media dir
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
