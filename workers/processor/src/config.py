"""Configuration and environment management for Reelix cloud processor."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


ALLOWED_GEMINI_MODELS = ("gemini-3.8-flash", "gemini-3.6-flash")
DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"


@dataclass
class WorkerConfig:
    # Google Gemini AI settings (Strictly restricted to gemini-3.8-flash and gemini-3.6-flash on Free Tier)
    gemini_api_key: Optional[str] = None
    gemini_model: str = DEFAULT_GEMINI_MODEL

    # Google Drive persistent storage settings (OAuth 2.0 on behalf of personal Google account)
    gdrive_client_id: Optional[str] = None
    gdrive_client_secret: Optional[str] = None
    gdrive_refresh_token: Optional[str] = None
    gdrive_access_token: Optional[str] = None

    # Legacy / Shared Drive Service Account settings (optional fallback)
    gdrive_service_account_json: Optional[str] = None
    gdrive_service_account_path: Optional[str] = None
    gdrive_folder_id: Optional[str] = None

    # Processing and execution limits
    max_jobs_per_run: int = 5
    whisper_model: str = "base"
    work_dir: Path = Path("/tmp/reelix_worker")
    runner_id: str = "local-runner"
    lease_timeout_ms: int = 900_000  # 15 minutes lease before an active job can be reclaimed

    # Testing / Dry-run mode
    mock_mode: bool = False

    def __post_init__(self):
        if self.gemini_model not in ALLOWED_GEMINI_MODELS:
            raise ValueError(
                f"Disallowed Gemini model '{self.gemini_model}'. Reelix is strictly restricted to "
                f"the following Google AI Studio free-tier models: {ALLOWED_GEMINI_MODELS}"
            )

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
            gemini_model=os.getenv("GEMINI_MODEL", DEFAULT_GEMINI_MODEL),
            gdrive_client_id=os.getenv("GDRIVE_CLIENT_ID"),
            gdrive_client_secret=os.getenv("GDRIVE_CLIENT_SECRET"),
            gdrive_refresh_token=os.getenv("GDRIVE_REFRESH_TOKEN"),
            gdrive_access_token=os.getenv("GDRIVE_ACCESS_TOKEN"),
            gdrive_service_account_json=os.getenv("GDRIVE_SERVICE_ACCOUNT_JSON"),
            gdrive_service_account_path=os.getenv("GDRIVE_SERVICE_ACCOUNT_PATH"),
            gdrive_folder_id=os.getenv("GDRIVE_FOLDER_ID"),
            max_jobs_per_run=int(os.getenv("MAX_JOBS_PER_RUN", "5")),
            whisper_model=os.getenv("WHISPER_MODEL", "base"),
            work_dir=work_dir,
            runner_id=runner_id,
            lease_timeout_ms=int(os.getenv("LEASE_TIMEOUT_MS", "900000")),
            mock_mode=os.getenv("MOCK_STORAGE", "").lower() in ("true", "1", "yes"),
        )
