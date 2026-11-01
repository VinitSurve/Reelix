"""Open-source Whisper speech-to-text transcription running on CPU."""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

logger = logging.getLogger(__name__)


@dataclass
class TranscriptionOutcome:
    success: bool
    transcript: Optional[str] = None
    model_name: str = "base"
    duration_seconds: float = 0.0
    error: Optional[str] = None


class WhisperTranscriber:
    """Executes offline Whisper transcription on CPU without paid APIs."""

    def __init__(self, model_name: str = "base"):
        self.model_name = model_name
        self._model: Any = None

    def _load_model(self):
        if self._model is not None:
            return self._model

        try:
            import whisper

            logger.info("Loading Whisper model '%s' on CPU...", self.model_name)
            start_t = time.time()
            self._model = whisper.load_model(self.model_name, device="cpu")
            load_duration = time.time() - start_t
            logger.info("Whisper model '%s' loaded in %.2fs", self.model_name, load_duration)
            return self._model
        except ImportError:
            logger.error("openai-whisper is not installed in the Python environment")
            raise

    def transcribe(self, wav_path: Path) -> TranscriptionOutcome:
        if not wav_path.exists() or wav_path.stat().st_size == 0:
            return TranscriptionOutcome(
                success=False,
                model_name=self.model_name,
                error="Audio WAV file does not exist or is empty",
            )

        start_t = time.time()
        try:
            model = self._load_model()
            logger.info("Starting Whisper transcription on %s (%d bytes)", wav_path.name, wav_path.stat().st_size)
            result = model.transcribe(
                str(wav_path),
                fp16=False,  # CPU requires fp32 (fp16=False)
                language=None,  # Auto-detect language
                verbose=False,
            )
            elapsed = time.time() - start_t
            text = (result.get("text") or "").strip()
            detected_lang = result.get("language", "unknown")
            logger.info("Whisper transcription completed in %.2fs (lang=%s, length=%d chars)", elapsed, detected_lang, len(text))

            return TranscriptionOutcome(
                success=True,
                transcript=text,
                model_name=self.model_name,
                duration_seconds=elapsed,
            )
        except Exception as e:
            elapsed = time.time() - start_t
            logger.error("Whisper transcription failed after %.2fs: %s", elapsed, e)
            return TranscriptionOutcome(
                success=False,
                model_name=self.model_name,
                duration_seconds=elapsed,
                error=f"Whisper inference error: {e}",
            )

