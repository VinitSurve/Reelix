"""Data models and serialization for cloud processing job manifests."""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional


def compute_file_sha256(file_path: Path | str, chunk_size: int = 65536) -> str:
    """Computes streaming SHA-256 digest of a local file in memory-efficient chunks."""
    p = Path(file_path)
    if not p.is_file():
        raise FileNotFoundError(f"File not found for SHA-256 computation: {file_path}")
    h = hashlib.sha256()
    with open(p, "rb") as f:
        while chunk := f.read(chunk_size):
            h.update(chunk)
    return h.hexdigest()


class JobStatus(str, Enum):
    QUEUED = "QUEUED"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    PARTIAL = "PARTIAL"
    FAILED = "FAILED"
    WAITING_RETRY = "WAITING_RETRY"
    NEEDS_ATTENTION = "NEEDS_ATTENTION"


class StageStatus(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    SKIPPED = "SKIPPED"
    FAILED = "FAILED"


@dataclass
class StageDetail:
    status: StageStatus = StageStatus.PENDING
    attempts: int = 0
    started_at: Optional[int] = None
    completed_at: Optional[int] = None
    last_error: Optional[str] = None
    next_retry_at: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "status": self.status.value,
            "attempts": self.attempts,
            "started_at": self.started_at,
            "completed_at": self.completed_at,
            "last_error": self.last_error,
            "next_retry_at": self.next_retry_at,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> StageDetail:
        return cls(
            status=StageStatus(data.get("status", StageStatus.PENDING.value)),
            attempts=data.get("attempts", 0),
            started_at=data.get("started_at"),
            completed_at=data.get("completed_at"),
            last_error=data.get("last_error"),
            next_retry_at=data.get("next_retry_at"),
        )


@dataclass
class ArtifactRef:
    drive_file_id: str
    mime_type: str = ""
    size_bytes: int = 0
    content_hash: Optional[str] = None
    name: Optional[str] = None
    generation: int = 1

    def to_dict(self) -> Dict[str, Any]:
        return {
            "drive_file_id": self.drive_file_id,
            "mime_type": self.mime_type,
            "size_bytes": self.size_bytes,
            "content_hash": self.content_hash,
            "name": self.name,
            "generation": self.generation,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> ArtifactRef:
        return cls(
            drive_file_id=data.get("drive_file_id", ""),
            mime_type=data.get("mime_type", ""),
            size_bytes=data.get("size_bytes", 0),
            content_hash=data.get("content_hash"),
            name=data.get("name"),
            generation=data.get("generation", 1),
        )


@dataclass
class MediaInfo:
    file_name: str
    mime_type: str
    drive_file_id: str
    size_bytes: int = 0
    duration_ms: Optional[int] = None
    content_hash: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> MediaInfo:
        return cls(
            file_name=data.get("file_name", ""),
            mime_type=data.get("mime_type", ""),
            drive_file_id=data.get("drive_file_id", ""),
            size_bytes=data.get("size_bytes", 0),
            duration_ms=data.get("duration_ms"),
            content_hash=data.get("content_hash"),
        )


@dataclass
class StageStates:
    acquisition: StageStatus = StageStatus.PENDING
    audio_extraction: StageStatus = StageStatus.PENDING
    transcription: StageStatus = StageStatus.PENDING
    ocr: StageStatus = StageStatus.PENDING
    gemini_analysis: StageStatus = StageStatus.PENDING

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> StageStates:
        return cls(
            acquisition=StageStatus(data.get("acquisition", StageStatus.PENDING.value)),
            audio_extraction=StageStatus(data.get("audio_extraction", StageStatus.PENDING.value)),
            transcription=StageStatus(data.get("transcription", StageStatus.PENDING.value)),
            ocr=StageStatus(data.get("ocr", StageStatus.PENDING.value)),
            gemini_analysis=StageStatus(data.get("gemini_analysis", StageStatus.PENDING.value)),
        )


@dataclass
class GeminiAnalysisResult:
    summary: str
    detailed_summary: str
    main_topic: str
    category: str
    keywords: List[str]
    tags: List[str]
    key_takeaways: List[str]
    important_concepts: List[str]
    actionable_steps: List[str]
    model_name: str
    generated_at: int

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> GeminiAnalysisResult:
        return cls(
            summary=data.get("summary", ""),
            detailed_summary=data.get("detailed_summary", ""),
            main_topic=data.get("main_topic", ""),
            category=data.get("category", ""),
            keywords=data.get("keywords", []),
            tags=data.get("tags", []),
            key_takeaways=data.get("key_takeaways", []),
            important_concepts=data.get("important_concepts", []),
            actionable_steps=data.get("actionable_steps", []),
            model_name=data.get("model_name", "gemini-3.6-flash"),
            generated_at=data.get("generated_at", 0),
        )


@dataclass
class JobResults:
    audio_drive_file_id: Optional[str] = None
    audio_size_bytes: Optional[int] = None
    audio_content_hash: Optional[str] = None
    transcript: Optional[str] = None
    transcript_model: Optional[str] = None
    ocr_text: Optional[str] = None
    analysis: Optional[GeminiAnalysisResult] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> JobResults:
        analysis_data = data.get("analysis")
        analysis = GeminiAnalysisResult.from_dict(analysis_data) if analysis_data else None
        return cls(
            audio_drive_file_id=data.get("audio_drive_file_id"),
            audio_size_bytes=data.get("audio_size_bytes"),
            audio_content_hash=data.get("audio_content_hash"),
            transcript=data.get("transcript"),
            transcript_model=data.get("transcript_model"),
            ocr_text=data.get("ocr_text"),
            analysis=analysis,
        )


@dataclass
class JobManifest:
    item_id: str
    source: str
    created_at: int
    updated_at: int
    schema_version: int = 1
    record_revision: int = 1
    version: int = 1
    generation: int = 1
    historical_generations: List[Dict[str, Any]] = field(default_factory=list)
    job_id: str = ""
    original_url: Optional[str] = None
    caption: Optional[str] = None
    user_notes: Optional[str] = None
    category: Optional[str] = None
    user_tags: List[str] = field(default_factory=list)
    status: JobStatus = JobStatus.QUEUED
    claimed_by: Optional[str] = None
    claimed_at: Optional[int] = None
    lease_timeout_ms: Optional[int] = 900_000
    etag: Optional[str] = None
    retry_count: int = 0
    max_retries: int = 3
    next_retry_at: Optional[int] = None
    error_message: Optional[str] = None
    media: Optional[MediaInfo] = None
    stages: StageStates = field(default_factory=StageStates)
    stage_details: Dict[str, StageDetail] = field(default_factory=dict)
    artifacts: Dict[str, ArtifactRef] = field(default_factory=dict)
    results: JobResults = field(default_factory=JobResults)

    def __post_init__(self):
        if not self.job_id:
            self.job_id = self.item_id
        if self.version != self.schema_version and self.schema_version != 1:
            self.version = self.schema_version

    def get_stage_detail(self, stage_name: str) -> StageDetail:
        if stage_name not in self.stage_details:
            self.stage_details[stage_name] = StageDetail()
        return self.stage_details[stage_name]

    def record_stage_update(
        self,
        stage_name: str,
        status: StageStatus,
        error: Optional[str] = None,
        now_ms: Optional[int] = None,
    ) -> None:
        import time
        now = now_ms or int(time.time() * 1000)
        detail = self.get_stage_detail(stage_name)
        detail.status = status
        detail.attempts += 1
        if status == StageStatus.RUNNING:
            detail.started_at = now
        elif status in (StageStatus.SUCCEEDED, StageStatus.SKIPPED):
            detail.completed_at = now
            detail.last_error = None
        elif status == StageStatus.FAILED:
            detail.completed_at = now
            detail.last_error = error

        # Also keep top-level stages in sync
        if hasattr(self.stages, stage_name):
            setattr(self.stages, stage_name, status)

    def to_dict(self) -> Dict[str, Any]:
        d = asdict(self)
        d["status"] = self.status.value
        d["schema_version"] = self.schema_version
        d["record_revision"] = self.record_revision
        d["version"] = self.version
        d["generation"] = self.generation
        d["historical_generations"] = self.historical_generations
        d["job_id"] = self.job_id
        d["stages"] = {
            "acquisition": self.stages.acquisition.value,
            "audio_extraction": self.stages.audio_extraction.value,
            "transcription": self.stages.transcription.value,
            "ocr": self.stages.ocr.value,
            "gemini_analysis": self.stages.gemini_analysis.value,
        }
        d["stage_details"] = {k: v.to_dict() for k, v in self.stage_details.items()}
        d["artifacts"] = {k: v.to_dict() for k, v in self.artifacts.items()}
        return d

    def to_json(self, indent: Optional[int] = 2) -> str:
        return json.dumps(self.to_dict(), indent=indent)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> JobManifest:
        media_data = data.get("media")
        media = MediaInfo.from_dict(media_data) if media_data else None

        stages_data = data.get("stages", {})
        stages = StageStates.from_dict(stages_data)
        # Backward compatibility: if older manifest already had uploaded media and no acquisition stage recorded,
        # acquisition was already handled / not required.
        if media is not None and "acquisition" not in stages_data:
            stages.acquisition = StageStatus.SKIPPED

        stage_details = {
            k: StageDetail.from_dict(v)
            for k, v in data.get("stage_details", {}).items()
        }

        artifacts = {
            k: ArtifactRef.from_dict(v)
            for k, v in data.get("artifacts", {}).items()
        }

        results_data = data.get("results", {})
        results = JobResults.from_dict(results_data)

        schema_version = data.get("schema_version", data.get("version", 1))
        record_revision = data.get("record_revision", data.get("version", 1))
        generation = data.get("generation", 1)
        historical_generations = data.get("historical_generations", [])

        return cls(
            schema_version=schema_version,
            record_revision=record_revision,
            version=data.get("version", schema_version),
            generation=generation,
            historical_generations=historical_generations,
            job_id=data.get("job_id", data["item_id"]),
            item_id=data["item_id"],
            source=data.get("source", "MEDIA_SHARE"),
            original_url=data.get("original_url"),
            caption=data.get("caption"),
            user_notes=data.get("user_notes"),
            category=data.get("category"),
            user_tags=data.get("user_tags", []),
            status=JobStatus(data.get("status", JobStatus.QUEUED.value)),
            claimed_by=data.get("claimed_by"),
            claimed_at=data.get("claimed_at"),
            lease_timeout_ms=data.get("lease_timeout_ms", 900_000),
            etag=data.get("etag"),
            created_at=data.get("created_at", 0),
            updated_at=data.get("updated_at", 0),
            retry_count=data.get("retry_count", 0),
            max_retries=data.get("max_retries", 3),
            next_retry_at=data.get("next_retry_at"),
            error_message=data.get("error_message"),
            media=media,
            stages=stages,
            stage_details=stage_details,
            artifacts=artifacts,
            results=results,
        )

    @classmethod
    def from_json(cls, json_str: str) -> JobManifest:
        return cls.from_dict(json.loads(json_str))
