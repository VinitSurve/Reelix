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

    def is_tesseract_available(self) -> bool:
        return shutil.which(self.tesseract_bin) is not None

    def extract_image(
        self,
        image_path: Path,
        timeout_seconds: int = 30,
        is_cancelled: Optional[Callable[[], bool]] = None,
    ) -> OcrExtractionOutcome:
        """Extracts on-screen text directly from a static image file using Tesseract."""
        if not image_path.exists() or image_path.stat().st_size == 0:
            return OcrExtractionOutcome(success=False, error=f"Image file does not exist or is empty: {image_path}")

        if is_cancelled and is_cancelled():
            return OcrExtractionOutcome(success=False, error="OCR extraction cancelled")

        if not self.is_tesseract_available():
            logger.info("Tesseract binary not installed; skipping image OCR")
            return OcrExtractionOutcome(success=True, text="", error=None)

        try:
            resolved_image = image_path.resolve()
            ocr_proc = subprocess.run(
                [self.tesseract_bin, str(resolved_image), "stdout", "--oem", "1", "-l", "eng"],
                capture_output=True,
                text=True,
                errors="replace",
                timeout=timeout_seconds,
                check=False,
            )
            extracted_lines: List[str] = []
            seen_normalized = set()

            if ocr_proc.returncode == 0 and ocr_proc.stdout:
                for line in ocr_proc.stdout.splitlines():
                    cleaned = line.strip()
                    norm = re.sub(r"[^\w\s]", "", cleaned).strip().lower()
                    if len(norm) >= 2 and norm not in seen_normalized:
                        seen_normalized.add(norm)
                        extracted_lines.append(cleaned)

            combined = "\n".join(extracted_lines).strip()
            return OcrExtractionOutcome(
                success=True,
                text=combined,
                frames_processed=1,
            )
        except Exception as e:
            logger.warning("Error running OCR on image %s: %s", image_path.name, e)
            return OcrExtractionOutcome(success=True, text="", error=str(e))

    def extract_carousel(
        self,
        items: List[Any],
        output_dir: Path,
        timeout_seconds: int = 60,
        is_cancelled: Optional[Callable[[], bool]] = None,
    ) -> OcrExtractionOutcome:
        """Extracts on-screen text across an ordered list of carousel items (images and/or videos)."""
        if not items:
            return OcrExtractionOutcome(success=True, text="", frames_processed=0)

        slide_texts: List[str] = []
        total_frames = 0

        for idx, item in enumerate(items):
            if is_cancelled and is_cancelled():
                return OcrExtractionOutcome(success=False, error="OCR extraction cancelled")

            # item can be a Path or a MediaItem object
            file_path = getattr(item, "local_path", None)
            if not file_path and isinstance(item, Path):
                file_path = item
            elif not file_path and hasattr(item, "file_name"):
                file_path = output_dir / item.file_name

            if not file_path or not Path(file_path).exists():
                continue

            path_obj = Path(file_path)
            media_type = getattr(item, "media_type", "").upper()
            is_image = media_type == "IMAGE" or path_obj.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp")

            if is_image:
                slide_res = self.extract_image(path_obj, timeout_seconds=timeout_seconds, is_cancelled=is_cancelled)
            else:
                slide_res = self.extract(path_obj, output_dir, timeout_seconds=timeout_seconds, is_cancelled=is_cancelled)

            total_frames += slide_res.frames_processed
            item_text = slide_res.text.strip()
            if hasattr(item, "ocr_text"):
                item.ocr_text = item_text

            if item_text:
                m_label = "Image" if is_image else "Video"
                slide_texts.append(f"Slide {idx + 1} ({m_label}):\n{item_text}")

        combined_text = "\n\n".join(slide_texts).strip()
        logger.info("Carousel OCR extracted text across %d items (%d frames/images processed)", len(items), total_frames)
        return OcrExtractionOutcome(
            success=True,
            text=combined_text,
            frames_processed=total_frames,
        )

    def extract(
        self,
        input_video: Path,
        output_dir: Path,
        max_frames: int = 3,
        timeout_seconds: int = 60,
        is_cancelled: Optional[Callable[[], bool]] = None,
    ) -> OcrExtractionOutcome:
        if not input_video.exists() or input_video.stat().st_size == 0:
            return OcrExtractionOutcome(success=False, error="Input media file does not exist or is empty")

        if is_cancelled and is_cancelled():
            return OcrExtractionOutcome(success=False, error="OCR extraction cancelled")

        # Route static images directly to extract_image
        if input_video.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp"):
            return self.extract_image(input_video, timeout_seconds=timeout_seconds, is_cancelled=is_cancelled)

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

