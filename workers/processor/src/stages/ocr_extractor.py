"""OCR visual text extraction stage for capturing on-screen text from video frames."""

from __future__ import annotations

import logging
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, List, Optional

logger = logging.getLogger(__name__)


@dataclass
class OcrExtractionOutcome:
    success: bool
    text: str = ""
    frames_processed: int = 0
    error: Optional[str] = None


class VisualTextExtractor:
    """Extracts on-screen visual text from video keyframes using FFmpeg and Tesseract OCR."""

    def __init__(
        self,
        ffmpeg_bin: Optional[str] = None,
        tesseract_bin: Optional[str] = None,
    ):
        self.ffmpeg_bin = ffmpeg_bin or shutil.which("ffmpeg") or "ffmpeg"
        self.tesseract_bin = tesseract_bin or shutil.which("tesseract") or "tesseract"

    def is_available(self) -> bool:
        return shutil.which(self.tesseract_bin) is not None and shutil.which(self.ffmpeg_bin) is not None

    def extract(
        self,
        input_video: Path,
        output_dir: Path,
        max_frames: int = 3,
        timeout_seconds: int = 60,
        is_cancelled: Optional[Callable[[], bool]] = None,
    ) -> OcrExtractionOutcome:
        if not input_video.exists() or input_video.stat().st_size == 0:
            return OcrExtractionOutcome(success=False, error="Input video file does not exist or is empty")

        if is_cancelled and is_cancelled():
            return OcrExtractionOutcome(success=False, error="OCR extraction cancelled")

        if not self.is_available():
            logger.info("Visual text extraction unavailable (ffmpeg or tesseract not installed); skipping OCR")
            return OcrExtractionOutcome(success=True, text="", error=None)

        resolved_video = input_video.resolve()
        resolved_output = output_dir.resolve()
        frames_dir = resolved_output / f"ocr_frames_{resolved_video.stem}"
        frames_dir.mkdir(parents=True, exist_ok=True)

        try:
            # 1. Sample up to max_frames keyframes across the video using FFmpeg
            frame_pattern = frames_dir / "frame_%02d.png"
            cmd_frames = [
                self.ffmpeg_bin,
                "-y",
                "-loglevel", "error",
                "-i", str(resolved_video),
                "-vf", "fps=1/5,scale=1280:-1",
                "-frames:v", str(max_frames),
                str(frame_pattern),
            ]
            subprocess.run(
                cmd_frames,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=timeout_seconds,
                check=False,
            )

            frame_files = sorted(frames_dir.glob("frame_*.png"))
            if not frame_files:
                # Fallback: extract at least the first frame at 00:00:01
                single_frame = frames_dir / "frame_01.png"
                cmd_single = [
                    self.ffmpeg_bin,
                    "-y",
                    "-loglevel", "error",
                    "-ss", "00:00:01",
                    "-i", str(resolved_video),
                    "-frames:v", "1",
                    str(single_frame),
                ]
                subprocess.run(
                    cmd_single,
                    capture_output=True,
                    text=True,
                    errors="replace",
                    timeout=timeout_seconds,
                    check=False,
                )
                if single_frame.exists():
                    frame_files = [single_frame]

            if not frame_files:
                return OcrExtractionOutcome(success=True, text="", frames_processed=0)

            # 2. Run OCR on each frame and aggregate clean text
            extracted_lines: List[str] = []
            seen_normalized = set()

            for frame in frame_files:
                if is_cancelled and is_cancelled():
                    return OcrExtractionOutcome(success=False, error="OCR extraction cancelled")
                try:
                    resolved_frame = frame.resolve()
                    ocr_proc = subprocess.run(
                        [self.tesseract_bin, str(resolved_frame), "stdout", "--oem", "1", "-l", "eng"],
                        capture_output=True,
                        text=True,
                        errors="replace",
                        timeout=30,
                        check=False,
                    )
                    if ocr_proc.returncode == 0 and ocr_proc.stdout:
                        for line in ocr_proc.stdout.splitlines():
                            cleaned = line.strip()
                            norm = re.sub(r"[^\w\s]", "", cleaned).strip().lower()
                            if len(norm) >= 3 and norm not in seen_normalized:
                                seen_normalized.add(norm)
                                extracted_lines.append(cleaned)
                except Exception as e:
                    logger.warning("OCR failed on frame %s: %s", frame.name, e)

            combined_text = "\n".join(extracted_lines).strip()
            logger.info("OCR extracted %d lines of visual text from %d frames", len(extracted_lines), len(frame_files))
            return OcrExtractionOutcome(
                success=True,
                text=combined_text,
                frames_processed=len(frame_files),
            )

        except Exception as e:
            logger.warning("Error during visual text OCR: %s", e)
            return OcrExtractionOutcome(success=True, text="", error=str(e))
        finally:
            shutil.rmtree(frames_dir, ignore_errors=True)

