"""Provider-independent media downloader abstraction and yt-dlp implementation."""

from __future__ import annotations

import abc
import enum
import logging
import os
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, Optional, Tuple

logger = logging.getLogger(__name__)


class DownloadStatus(str, enum.Enum):
    """Classification of media acquisition outcomes."""
    SUCCESS = "SUCCESS"
    UNAVAILABLE = "UNAVAILABLE"           # Private, deleted, requires login, age-restricted
    TRANSIENT_FAILURE = "TRANSIENT_FAILURE"  # Rate limit (429), network timeout, 5xx error
    UNSUPPORTED_URL = "UNSUPPORTED_URL"      # Malformed URL or unsupported platform


@dataclass
class DownloadResult:
    """Outcome and metadata for a media acquisition attempt."""
    status: DownloadStatus
    media_path: Optional[Path] = None
    mime_type: str = "video/mp4"
    file_name: Optional[str] = None
    size_bytes: int = 0
    duration_ms: Optional[int] = None
    title: Optional[str] = None
    caption: Optional[str] = None
    thumbnail_url: Optional[str] = None
    error_message: Optional[str] = None
    is_retryable: bool = False
    provider: str = "yt-dlp"

    @property
    def is_success(self) -> bool:
        return self.status == DownloadStatus.SUCCESS and self.media_path is not None and self.media_path.exists()


class MediaDownloader(abc.ABC):
    """Abstract interface for provider-independent media acquisition."""

    @abc.abstractmethod
    def download(self, url: str, output_dir: Path, item_id: str) -> DownloadResult:
        """Downloads the media for the specified URL into output_dir.

        Args:
            url: The public Instagram or media URL.
            output_dir: Directory where the media file should be saved.
            item_id: Reelix item identifier used for naming.

        Returns:
            DownloadResult describing success or categorized failure.
        """
        pass


class YtDlpDownloader(MediaDownloader):
    """Provider-independent media downloader using yt-dlp.

    Configured for Instagram Reels extraction into MP4 without requiring
    authentication, credentials, or cookies.
    """

    # Patterns indicating permanent unavailability (do not retry: private accounts, deleted posts, 404s)
    PERMANENT_PATTERNS = [
        re.compile(r"account\s+is\s+private", re.IGNORECASE),
        re.compile(r"user\s+is\s+private", re.IGNORECASE),
        re.compile(r"video\s+is\s+private", re.IGNORECASE),
        re.compile(r"post\s+has\s+been\s+removed", re.IGNORECASE),
        re.compile(r"content\s+has\s+been\s+removed", re.IGNORECASE),
        re.compile(r"page\s+not\s+found", re.IGNORECASE),
        re.compile(r"404(\s+not\s+found)?", re.IGNORECASE),
        re.compile(r"content\s+is\s+not\s+available", re.IGNORECASE),
        re.compile(r"this\s+video\s+has\s+been\s+deleted", re.IGNORECASE),
    ]

    # Patterns indicating unsupported URL
    UNSUPPORTED_PATTERNS = [
        re.compile(r"unsupported\s+url", re.IGNORECASE),
        re.compile(r"is\s+not\s+a\s+valid\s+url", re.IGNORECASE),
        re.compile(r"no\s+suitable\s+extractor", re.IGNORECASE),
    ]

    # Patterns indicating temporary restrictions, login challenges, rate limits, or transient failure (eligible for retry)
    TRANSIENT_PATTERNS = [
        re.compile(r"login\s+required", re.IGNORECASE),
        re.compile(r"please\s+log\s+in", re.IGNORECASE),
        re.compile(r"checkpoint", re.IGNORECASE),
        re.compile(r"challenge", re.IGNORECASE),
        re.compile(r"redirected\s+to\s+login", re.IGNORECASE),
        re.compile(r"429", re.IGNORECASE),
        re.compile(r"too\s+many\s+requests", re.IGNORECASE),
        re.compile(r"rate\s+limit", re.IGNORECASE),
        re.compile(r"throttl", re.IGNORECASE),
        re.compile(r"rate\s+limit", re.IGNORECASE),
        re.compile(r"timed?\s*out", re.IGNORECASE),
        re.compile(r"connection\s+reset", re.IGNORECASE),
        re.compile(r"connection\s+refused", re.IGNORECASE),
        re.compile(r"500", re.IGNORECASE),
        re.compile(r"502", re.IGNORECASE),
        re.compile(r"503", re.IGNORECASE),
        re.compile(r"504", re.IGNORECASE),
        re.compile(r"temporary", re.IGNORECASE),
        re.compile(r"network\s+unreachable", re.IGNORECASE),
    ]

    def __init__(
        self,
        max_retries: int = 2,
        backoff_base_seconds: float = 1.0,
        backoff_max_seconds: float = 8.0,
        timeout_seconds: int = 60,
        max_file_size_bytes: int = 150 * 1024 * 1024,
        ydl_factory: Optional[Callable[[Dict[str, Any]], Any]] = None,
    ):
        self.max_retries = max_retries
        self.backoff_base_seconds = backoff_base_seconds
        self.backoff_max_seconds = backoff_max_seconds
        self.timeout_seconds = timeout_seconds
        self.max_file_size_bytes = max_file_size_bytes
        self._ydl_factory = ydl_factory

    def classify_error(self, error_message: str) -> Tuple[DownloadStatus, bool]:
        """Classifies an error string into a DownloadStatus and is_retryable flag."""
        for pattern in self.PERMANENT_PATTERNS:
            if pattern.search(error_message):
                return DownloadStatus.UNAVAILABLE, False

        for pattern in self.UNSUPPORTED_PATTERNS:
            if pattern.search(error_message):
                return DownloadStatus.UNSUPPORTED_URL, False

        for pattern in self.TRANSIENT_PATTERNS:
            if pattern.search(error_message):
                return DownloadStatus.TRANSIENT_FAILURE, True

        # Default fallback: Treat unknown download errors as permanent to avoid endless loops
        return DownloadStatus.UNAVAILABLE, False

    def _get_ydl_instance(self, opts: Dict[str, Any]):
        if self._ydl_factory is not None:
            return self._ydl_factory(opts)
        import yt_dlp
        return yt_dlp.YoutubeDL(opts)

    def download(self, url: str, output_dir: Path, item_id: str) -> DownloadResult:
        """Attempts media acquisition via yt-dlp with bounded exponential backoff."""
        if not url or not (url.startswith("http://") or url.startswith("https://")):
            return DownloadResult(
                status=DownloadStatus.UNSUPPORTED_URL,
                error_message=f"Invalid URL schema: {url}",
                is_retryable=False,
                provider="yt-dlp",
            )

        output_dir.mkdir(parents=True, exist_ok=True)
        outtmpl = str(output_dir / f"{item_id}_%(id)s.%(ext)s")

        ydl_opts: Dict[str, Any] = {
            "outtmpl": outtmpl,
            "format": "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
            "merge_output_format": "mp4",
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "socket_timeout": self.timeout_seconds,
            "max_filesize": self.max_file_size_bytes,
            "prefer_ffmpeg": True,
        }

        last_error = ""
        last_status = DownloadStatus.TRANSIENT_FAILURE
        is_retryable = False

        for attempt in range(self.max_retries + 1):
            if attempt > 0:
                sleep_seconds = min(
                    self.backoff_base_seconds * (2 ** (attempt - 1)),
                    self.backoff_max_seconds,
                )
                logger.warning(
                    "Retrying yt-dlp acquisition for item %s (attempt %d/%d) after %.1fs backoff...",
                    item_id, attempt, self.max_retries, sleep_seconds
                )
                time.sleep(sleep_seconds)

            try:
                logger.info("Starting yt-dlp download for item %s from %s", item_id, url)
                with self._get_ydl_instance(ydl_opts) as ydl:
                    info = ydl.extract_info(url, download=True)
                    if not info or not isinstance(info, dict):
                        last_error = f"yt-dlp returned empty or invalid info: {type(info)}"
                        last_status = DownloadStatus.UNAVAILABLE
                        is_retryable = False
                        break

                    # Locate the downloaded file
                    media_path = self._find_downloaded_file(output_dir, item_id, info)
                    if not media_path or not media_path.exists() or media_path.stat().st_size == 0:
                        last_error = f"Downloaded media file not found or empty in {output_dir}"
                        last_status = DownloadStatus.UNAVAILABLE
                        is_retryable = False
                        break

                    # Successful download
                    file_size = media_path.stat().st_size
                    duration_sec = info.get("duration")
                    duration_ms = int(duration_sec * 1000) if isinstance(duration_sec, (int, float)) else None
                    title = str(info["title"]) if info.get("title") else None
                    caption = str(info["description"]) if info.get("description") else None
                    thumbnail_url = str(info["thumbnail"]) if info.get("thumbnail") else None

                    logger.info(
                        "yt-dlp successfully downloaded media for item %s: %s (%d bytes)",
                        item_id, media_path.name, file_size
                    )
                    return DownloadResult(
                        status=DownloadStatus.SUCCESS,
                        media_path=media_path,
                        mime_type="video/mp4",
                        file_name=media_path.name,
                        size_bytes=file_size,
                        duration_ms=duration_ms,
                        title=title,
                        caption=caption,
                        thumbnail_url=thumbnail_url,
                        provider="yt-dlp",
                    )

            except Exception as e:
                err_str = str(e)
                last_error = err_str
                last_status, is_retryable = self.classify_error(err_str)
                logger.warning(
                    "yt-dlp attempt %d failed for item %s: %s (classified as %s, retryable=%s)",
                    attempt, item_id, err_str, last_status.value, is_retryable
                )
                if not is_retryable:
                    break

        logger.error(
            "yt-dlp media acquisition concluded with status %s for item %s: %s",
            last_status.value, item_id, last_error
        )
        return DownloadResult(
            status=last_status,
            error_message=last_error,
            is_retryable=is_retryable,
            provider="yt-dlp",
        )

    def _find_downloaded_file(
        self,
        output_dir: Path,
        item_id: str,
        info: Dict[str, Any],
    ) -> Optional[Path]:
        """Locates the final downloaded media file on disk."""
        # Check info['_filename']
        if "_filename" in info and info["_filename"]:
            cand = Path(info["_filename"])
            if cand.exists() and cand.stat().st_size > 0:
                return cand

        # Check requested_downloads
        for req in info.get("requested_downloads", []):
            if "filepath" in req and req["filepath"]:
                cand = Path(req["filepath"])
                if cand.exists() and cand.stat().st_size > 0:
                    return cand

        # Fallback: scan output_dir for matching item_id prefix
        matches = list(output_dir.glob(f"{item_id}_*"))
        if matches:
            # Sort by modification time descending
            matches.sort(key=lambda p: p.stat().st_mtime, reverse=True)
            for m in matches:
                if m.is_file() and m.stat().st_size > 0 and not m.name.endswith(".part"):
                    return m

        return None
