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


class TransitionType(str, Enum):
    PROMOTED = "PROMOTED"
    CLAIM = "CLAIM"
    ACQUIRE = "ACQUIRE"
    AUDIO_EXTRACTED = "AUDIO_EXTRACTED"
    TRANSCRIBED = "TRANSCRIBED"
    OCR_EXTRACTED = "OCR_EXTRACTED"
    ANALYZED = "ANALYZED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    FORK_FENCED = "FORK_FENCED"
    HEARTBEAT = "HEARTBEAT"


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
class TransitionRecord:
    item_id: str
    generation: int
    attempt_epoch: int
    transition_seq: int
    transition_type: TransitionType
    attempt_id: str
    operation_id: Optional[str] = None
    previous_transition_id: Optional[str] = None
    previous_hash: Optional[str] = None
    payload: Dict[str, Any] = field(default_factory=dict)
    payload_integrity_hash: str = ""
    created_at: int = 0
    deterministic_transition_id: str = ""
    pregenerated_file_id: Optional[str] = None

    def __post_init__(self):
        if not self.deterministic_transition_id:
            self.deterministic_transition_id = (
                f"g{self.generation}_e{self.attempt_epoch}_s{self.transition_seq:02d}_{self.transition_type.value}"
            )
        if not self.payload_integrity_hash:
            self.payload_integrity_hash = self.compute_payload_hash(self.payload)

    @staticmethod
    def compute_payload_hash(payload: Dict[str, Any]) -> str:
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    def compute_transition_hash(self) -> str:
        prev = self.previous_hash or "ROOT"
        content = f"{prev}:{self.deterministic_transition_id}:{self.payload_integrity_hash}"
        return hashlib.sha256(content.encode("utf-8")).hexdigest()

    def matches_identity_and_payload(self, other: TransitionRecord) -> bool:
        """6-point verification between two transition records."""
        return (
            self.item_id == other.item_id
            and self.generation == other.generation
            and self.attempt_epoch == other.attempt_epoch
            and self.attempt_id == other.attempt_id
            and self.transition_type == other.transition_type
            and self.payload_integrity_hash == other.payload_integrity_hash
        )

    def to_dict(self) -> Dict[str, Any]:
        return {
            "item_id": self.item_id,
            "generation": self.generation,
            "attempt_epoch": self.attempt_epoch,
            "transition_seq": self.transition_seq,
            "transition_type": self.transition_type.value,
            "attempt_id": self.attempt_id,
            "operation_id": self.operation_id,
            "previous_transition_id": self.previous_transition_id,
            "previous_hash": self.previous_hash,
            "payload": self.payload,
            "payload_integrity_hash": self.payload_integrity_hash,
            "created_at": self.created_at,
            "deterministic_transition_id": self.deterministic_transition_id,
            "pregenerated_file_id": self.pregenerated_file_id,
        }

    def to_json(self, indent: Optional[int] = 2) -> str:
        return json.dumps(self.to_dict(), indent=indent)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> TransitionRecord:
        ttype = TransitionType(data["transition_type"])
        return cls(
            item_id=data["item_id"],
            generation=data.get("generation", 1),
            attempt_epoch=data.get("attempt_epoch", 1),
            transition_seq=data.get("transition_seq", 1),
            transition_type=ttype,
            attempt_id=data.get("attempt_id", ""),
            operation_id=data.get("operation_id"),
            previous_transition_id=data.get("previous_transition_id"),
            previous_hash=data.get("previous_hash"),
            payload=data.get("payload", {}),
            payload_integrity_hash=data.get("payload_integrity_hash", ""),
            created_at=data.get("created_at", 0),
            deterministic_transition_id=data.get("deterministic_transition_id", ""),
            pregenerated_file_id=data.get("pregenerated_file_id"),
        )

    @classmethod
    def from_json(cls, json_str: str) -> TransitionRecord:
        return cls.from_dict(json.loads(json_str))


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
    active_generation: int = 1
    active_attempt_epoch: int = 1
    active_attempt_id: Optional[str] = None
    projection_version: int = 1
    last_transition_id: Optional[str] = None
    last_transition_hash: Optional[str] = None

    def __post_init__(self):
        if not self.job_id:
            self.job_id = self.item_id
        if self.version != self.schema_version and self.schema_version != 1:
            self.version = self.schema_version
        if self.active_generation != self.generation and self.generation != 1:
            self.active_generation = self.generation

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
        d["active_generation"] = self.active_generation
        d["active_attempt_epoch"] = self.active_attempt_epoch
        d["active_attempt_id"] = self.active_attempt_id
        d["projection_version"] = self.projection_version
        d["last_transition_id"] = self.last_transition_id
        d["last_transition_hash"] = self.last_transition_hash
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
            active_generation=data.get("active_generation", generation),
            active_attempt_epoch=data.get("active_attempt_epoch", 1),
            active_attempt_id=data.get("active_attempt_id", data.get("claimed_by")),
            projection_version=data.get("projection_version", data.get("version", 1)),
            last_transition_id=data.get("last_transition_id"),
            last_transition_hash=data.get("last_transition_hash"),
        )

    @classmethod
    def from_json(cls, json_str: str) -> JobManifest:
        return cls.from_dict(json.loads(json_str))


def fold_transitions(
    item_id: str,
    transitions: List[TransitionRecord],
    base_manifest: Optional[JobManifest] = None,
) -> tuple[JobManifest, List[str]]:
    """Deterministically reconstructs an authoritative JobManifest projection from an immutable transition log.

    Handles:
    - Deduplicating identical transition records (same gen, epoch, seq, and payload hash).
    - Detecting integrity forks (same gen, epoch, seq with divergent payload hashes).
    - Ignoring stale records from superseded generations and superseded attempt epochs.
    - Stopping accumulation on missing sequence gaps or broken hash chains.
    - Monotonically calculating projection_version.
    """
    warnings: List[str] = []

    if not transitions:
        if base_manifest is not None:
            return base_manifest, warnings
        now_ms = 0
        return (
            JobManifest(
                item_id=item_id,
                source="MEDIA_SHARE",
                created_at=now_ms,
                updated_at=now_ms,
            ),
            warnings,
        )

    # 1. Highest generation determines active generation
    max_gen = max(t.generation for t in transitions)
    gen_transitions = [t for t in transitions if t.generation == max_gen]

    # 2. Highest attempt epoch within active generation
    max_epoch = max(t.attempt_epoch for t in gen_transitions)
    epoch_transitions = [t for t in gen_transitions if t.attempt_epoch == max_epoch]

    # 3. Deduplication and Fork Detection by transition_seq
    seq_groups: Dict[int, List[TransitionRecord]] = {}
    for t in epoch_transitions:
        seq_groups.setdefault(t.transition_seq, []).append(t)

    deduplicated_records: List[TransitionRecord] = []
    has_fork = False
    for seq in sorted(seq_groups.keys()):
        records = seq_groups[seq]
        first = records[0]
        # Check if all records in this sequence slot share identical payload hashes
        divergent = any(r.payload_integrity_hash != first.payload_integrity_hash for r in records[1:])
        if divergent:
            msg = f"Integrity fork detected for item {item_id} at gen={max_gen}, epoch={max_epoch}, seq={seq}: multiple conflicting payloads"
            warnings.append(msg)
            has_fork = True
            break
        # Deduplicate identical records
        deduplicated_records.append(first)

    # 4. Hash Chain and Continuity Validation
    valid_chain: List[TransitionRecord] = []
    expected_seq = 1
    expected_prev_hash: Optional[str] = None

    for t in deduplicated_records:
        if t.transition_seq != expected_seq:
            warnings.append(
                f"Sequence gap detected for item {item_id} at seq={t.transition_seq} (expected {expected_seq}); halting transition folding"
            )
            break
        if expected_seq > 1 and t.previous_hash != expected_prev_hash:
            warnings.append(
                f"Broken hash chain for item {item_id} at seq={t.transition_seq}: expected prev_hash {expected_prev_hash}, found {t.previous_hash}; halting fold"
            )
            break

        valid_chain.append(t)
        expected_prev_hash = t.compute_transition_hash()
        expected_seq += 1

    # 5. Fold transitions into JobManifest projection
    if base_manifest is not None:
        manifest = JobManifest.from_dict(base_manifest.to_dict())
    else:
        manifest = JobManifest(
            item_id=item_id,
            source="MEDIA_SHARE",
            created_at=valid_chain[0].created_at if valid_chain else 0,
            updated_at=valid_chain[-1].created_at if valid_chain else 0,
        )

    manifest.generation = max_gen
    manifest.active_generation = max_gen
    manifest.active_attempt_epoch = max_epoch
    manifest.projection_version = (manifest.projection_version or 1) + len(valid_chain)

    for t in valid_chain:
        manifest.active_attempt_id = t.attempt_id
        manifest.last_transition_id = t.deterministic_transition_id
        manifest.last_transition_hash = t.compute_transition_hash()
        manifest.updated_at = max(manifest.updated_at, t.created_at)

        if t.transition_type == TransitionType.PROMOTED:
            manifest.status = JobStatus.QUEUED
            manifest.claimed_by = None
            manifest.claimed_at = None
            manifest.stages = StageStates()
            manifest.stage_details = {}
            manifest.artifacts = {}
            manifest.results = JobResults()
        elif t.transition_type == TransitionType.CLAIM:
            manifest.status = JobStatus.PROCESSING
            manifest.claimed_by = t.attempt_id
            manifest.claimed_at = t.created_at
            manifest.lease_timeout_ms = t.payload.get("lease_timeout_ms", 900_000)
        elif t.transition_type == TransitionType.ACQUIRE:
            st = StageStatus(t.payload.get("status", StageStatus.SUCCEEDED.value))
            if "media" in t.payload:
                manifest.media = MediaInfo.from_dict(t.payload["media"])
            manifest.stages.acquisition = st
            manifest.record_stage_update("acquisition", st, now_ms=t.created_at)
        elif t.transition_type == TransitionType.AUDIO_EXTRACTED:
            st = StageStatus(t.payload.get("status", StageStatus.SUCCEEDED.value))
            if "artifact" in t.payload:
                art = ArtifactRef.from_dict(t.payload["artifact"])
                manifest.artifacts["audio_extraction"] = art
                manifest.results.audio_drive_file_id = art.drive_file_id
                manifest.results.audio_size_bytes = art.size_bytes
                manifest.results.audio_content_hash = art.content_hash
            manifest.stages.audio_extraction = st
            manifest.record_stage_update("audio_extraction", st, now_ms=t.created_at)
        elif t.transition_type == TransitionType.TRANSCRIBED:
            st = StageStatus(t.payload.get("status", StageStatus.SUCCEEDED.value))
            if "transcript" in t.payload:
                manifest.results.transcript = t.payload["transcript"]
                manifest.results.transcript_model = t.payload.get("transcript_model", "base")
            manifest.stages.transcription = st
            manifest.record_stage_update("transcription", st, now_ms=t.created_at)
        elif t.transition_type == TransitionType.OCR_EXTRACTED:
            st = StageStatus(t.payload.get("status", StageStatus.SUCCEEDED.value))
            if "ocr_text" in t.payload:
                manifest.results.ocr_text = t.payload["ocr_text"]
            manifest.stages.ocr = st
            manifest.record_stage_update("ocr", st, now_ms=t.created_at)
        elif t.transition_type == TransitionType.ANALYZED:
            st = StageStatus(t.payload.get("status", StageStatus.SUCCEEDED.value))
            if "analysis" in t.payload:
                manifest.results.analysis = GeminiAnalysisResult.from_dict(t.payload["analysis"])
            manifest.stages.gemini_analysis = st
            manifest.record_stage_update("gemini_analysis", st, now_ms=t.created_at)
        elif t.transition_type == TransitionType.COMPLETED:
            manifest.status = JobStatus.COMPLETED
        elif t.transition_type == TransitionType.FAILED:
            manifest.status = JobStatus.FAILED
            manifest.error_message = t.payload.get("error_message", "Job processing failed")
        elif t.transition_type == TransitionType.CANCELLED:
            manifest.status = JobStatus.NEEDS_ATTENTION
            manifest.error_message = t.payload.get("error_message", "Cancelled by user")
        elif t.transition_type == TransitionType.FORK_FENCED:
            manifest.status = JobStatus.PROCESSING
            manifest.claimed_by = t.attempt_id
            manifest.claimed_at = t.created_at
        elif t.transition_type == TransitionType.HEARTBEAT:
            manifest.claimed_at = t.created_at
            if "lease_timeout_ms" in t.payload:
                manifest.lease_timeout_ms = t.payload["lease_timeout_ms"]

    if has_fork:
        manifest.status = JobStatus.NEEDS_ATTENTION
        manifest.error_message = f"Integrity fork detected in attempt epoch {max_epoch}; epoch advance required"

    return manifest, warnings
