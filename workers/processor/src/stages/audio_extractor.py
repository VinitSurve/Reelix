"""FFmpeg audio extraction stage for converting video into 16kHz mono WAV."""

from __future__ import annotations

import logging
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

logger = logging.getLogger(__name__)


@dataclass
class AudioExtractionOutcome:
    success: bool
    wav_path: Optional[Path] = None
    size_bytes: int = 0
    error: Optional[str] = None
    no_audio_track: bool = False


class FFmpegAudioExtractor:
    """Extracts 16kHz 16-bit mono WAV audio from video files using system ffmpeg."""

    def __init__(self, ffmpeg_bin: Optional[str] = None):
        self.ffmpeg_bin = ffmpeg_bin or shutil.which("ffmpeg") or "ffmpeg"

    def is_available(self) -> bool:
        return shutil.which(self.ffmpeg_bin) is not None

    def extract(
        self,
        input_video: Path,
        output_wav: Path,
        timeout_seconds: int = 180,
        is_cancelled: Optional[Callable[[], bool]] = None,
    ) -> AudioExtractionOutcome:
        if not input_video.exists() or input_video.stat().st_size == 0:
            return AudioExtractionOutcome(success=False, error="Input video file does not exist or is empty")

        if not self.is_available():
            return AudioExtractionOutcome(success=False, error="ffmpeg binary is not installed on the runner")

        output_wav.parent.mkdir(parents=True, exist_ok=True)

        cmd = [
            self.ffmpeg_bin,
            "-y",                     # Overwrite output
            "-loglevel", "error",     # Suppress banner and noise
            "-i", str(input_video),   # Input file
            "-vn",                    # Discard video
            "-acodec", "pcm_s16le",   # Standard 16-bit PCM
            "-ar", "16000",           # 16,000 Hz sample rate
            "-ac", "1",               # Mono audio
            str(output_wav),
        ]

        try:
            logger.info("Executing ffmpeg audio extraction: %s -> %s", input_video.name, output_wav.name)
            if is_cancelled is None:
                res = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=timeout_seconds,
                    check=False,
                )
                returncode = res.returncode
                stderr = res.stderr
            else:
                proc = subprocess.Popen(
                    cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                )

                start_t = time.time()
                while proc.poll() is None:
                    if is_cancelled():
                        logger.warning("Propagating cancellation to active FFmpeg process")
                        proc.terminate()
                        try:
                            proc.wait(timeout=2)
                        except subprocess.TimeoutExpired:
                            proc.kill()
                        if output_wav.exists():
                            output_wav.unlink(missing_ok=True)
                        return AudioExtractionOutcome(success=False, error="FFmpeg audio extraction cancelled due to lease loss")

                    if time.time() - start_t > timeout_seconds:
                        proc.kill()
                        if output_wav.exists():
                            output_wav.unlink(missing_ok=True)
                        return AudioExtractionOutcome(success=False, error=f"ffmpeg timed out after {timeout_seconds}s")

                    time.sleep(0.1)

                _, stderr = proc.communicate()
                returncode = proc.returncode

            if returncode != 0:
                stderr_text = stderr.strip() if stderr else ""
                if "does not contain any stream" in stderr_text or "Output file does not contain any stream" in stderr_text:
                    logger.warning("Video has no audio track: %s", input_video.name)
                    return AudioExtractionOutcome(success=False, no_audio_track=True, error="No audio track found in media")
                return AudioExtractionOutcome(success=False, error=f"ffmpeg failed (code {returncode}): {stderr_text}")

            if not output_wav.exists() or output_wav.stat().st_size == 0:
                return AudioExtractionOutcome(success=False, error="ffmpeg succeeded but output WAV is empty or missing")

            size = output_wav.stat().st_size
            logger.info("Successfully extracted audio (%d bytes): %s", size, output_wav.name)
            return AudioExtractionOutcome(success=True, wav_path=output_wav, size_bytes=size)

        except subprocess.TimeoutExpired:
            if output_wav.exists():
                output_wav.unlink(missing_ok=True)
            return AudioExtractionOutcome(success=False, error=f"ffmpeg timed out after {timeout_seconds}s")
        except Exception as e:
            if output_wav.exists():
                output_wav.unlink(missing_ok=True)
            return AudioExtractionOutcome(success=False, error=f"Unexpected error running ffmpeg: {e}")

