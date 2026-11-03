"""Data models and serialization for cloud processing job manifests."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional


class JobStatus(str, Enum):
    QUEUED = "QUEUED"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    PARTIAL = "PARTIAL"
    FAILED = "FAILED"
    WAITING_RETRY = "WAITING_RETRY"


class StageStatus(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    SKIPPED = "SKIPPED"
    FAILED = "FAILED"


@dataclass
class MediaInfo:
    file_name: str
    mime_type: str
    drive_file_id: str
    size_bytes: int = 0
    duration_ms: Optional[int] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> MediaInfo:
        return cls(
            file_name=data.get("file_name", ""),
            mime_type=data.get("mime_type", ""),
            drive_file_id=data.get("drive_file_id", ""),
            size_bytes=data.get("size_bytes", 0),
            duration_ms=data.get("duration_ms"),
        )


@dataclass
class StageStates:
    acquisition: StageStatus = StageStatus.PENDING
    audio_extraction: StageStatus = StageStatus.PENDING
    transcription: StageStatus = StageStatus.PENDING
    gemini_analysis: StageStatus = StageStatus.PENDING

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> StageStates:
        return cls(
            acquisition=StageStatus(data.get("acquisition", StageStatus.PENDING.value)),
            audio_extraction=StageStatus(data.get("audio_extraction", StageStatus.PENDING.value)),
            transcription=StageStatus(data.get("transcription", StageStatus.PENDING.value)),
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
    transcript: Optional[str] = None
    transcript_model: Optional[str] = None
    analysis: Optional[GeminiAnalysisResult] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> JobResults:
        analysis_data = data.get("analysis")
        analysis = GeminiAnalysisResult.from_dict(analysis_data) if analysis_data else None
        return cls(
            audio_drive_file_id=data.get("audio_drive_file_id"),
            audio_size_bytes=data.get("audio_size_bytes"),
            transcript=data.get("transcript"),
            transcript_model=data.get("transcript_model"),
            analysis=analysis,
        )


@dataclass
class JobManifest:
    item_id: str
    source: str
    created_at: int
    updated_at: int
    version: int = 1
    original_url: Optional[str] = None
    caption: Optional[str] = None
    user_notes: Optional[str] = None
    status: JobStatus = JobStatus.QUEUED
    claimed_by: Optional[str] = None
    claimed_at: Optional[int] = None
    lease_timeout_ms: Optional[int] = 900_000
    etag: Optional[str] = None
    retry_count: int = 0
    max_retries: int = 3
    error_message: Optional[str] = None
    media: Optional[MediaInfo] = None
    stages: StageStates = field(default_factory=StageStates)
    results: JobResults = field(default_factory=JobResults)

    def to_dict(self) -> Dict[str, Any]:
        d = asdict(self)
        d["status"] = self.status.value
        d["stages"] = {
            "acquisition": self.stages.acquisition.value,
            "audio_extraction": self.stages.audio_extraction.value,
            "transcription": self.stages.transcription.value,
            "gemini_analysis": self.stages.gemini_analysis.value,
        }
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

        results_data = data.get("results", {})
        results = JobResults.from_dict(results_data)

        return cls(
            version=data.get("version", 1),
            item_id=data["item_id"],
            source=data.get("source", "MEDIA_SHARE"),
            original_url=data.get("original_url"),
            caption=data.get("caption"),
            user_notes=data.get("user_notes"),
            status=JobStatus(data.get("status", JobStatus.QUEUED.value)),
            claimed_by=data.get("claimed_by"),
            claimed_at=data.get("claimed_at"),
            lease_timeout_ms=data.get("lease_timeout_ms", 900_000),
            etag=data.get("etag"),
            created_at=data.get("created_at", 0),
            updated_at=data.get("updated_at", 0),
            retry_count=data.get("retry_count", 0),
            max_retries=data.get("max_retries", 3),
            error_message=data.get("error_message"),
            media=media,
            stages=stages,
            results=results,
        )

    @classmethod
    def from_json(cls, json_str: str) -> JobManifest:
        return cls.from_dict(json.loads(json_str))
