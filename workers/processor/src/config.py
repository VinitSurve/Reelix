"""Configuration and environment management for Reelix cloud processor."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


@dataclass
class WorkerConfig:
    # Google Gemini AI settings
    gemini_api_key: Optional[str] = None
    gemini_model: str = "gemini-1.5-flash"

    # Google Drive persistent storage settings
    gdrive_service_account_json: Optional[str] = None
    gdrive_service_account_path: Optional[str] = None
    gdrive_folder_id: Optional[str] = None

    # Processing and execution limits
    max_jobs_per_run: int = 5
    whisper_model: str = "base"
    work_dir: Path = Path("/tmp/reelix_worker")
    runner_id: str = "local-runner"

    # Testing / Dry-run mode
    mock_mode: bool = False

    @classmethod
    def from_env(cls) -> WorkerConfig:
        run_id = os.getenv("GITHUB_RUN_ID", "local")
        run_attempt = os.getenv("GITHUB_RUN_ATTEMPT", "1")
        runner_id = f"gha-{run_id}-{run_attempt}" if run_id != "local" else f"local-{os.getpid()}"

        work_dir_env = os.getenv("REELIX_WORK_DIR", "/tmp/reelix_worker")
        work_dir = Path(work_dir_env)
        work_dir.mkdir(parents=True, exist_ok=True)

        return cls(
            gemini_api_key=os.getenv("GEMINI_API_KEY"),
            gemini_model=os.getenv("GEMINI_MODEL", "gemini-1.5-flash"),
            gdrive_service_account_json=os.getenv("GDRIVE_SERVICE_ACCOUNT_JSON"),
            gdrive_service_account_path=os.getenv("GDRIVE_SERVICE_ACCOUNT_PATH"),
            gdrive_folder_id=os.getenv("GDRIVE_FOLDER_ID"),
            max_jobs_per_run=int(os.getenv("MAX_JOBS_PER_RUN", "5")),
            whisper_model=os.getenv("WHISPER_MODEL", "base"),
            work_dir=work_dir,
            runner_id=runner_id,
            mock_mode=os.getenv("REELIX_MOCK_MODE", "false").lower() in ("true", "1", "yes"),
        )
