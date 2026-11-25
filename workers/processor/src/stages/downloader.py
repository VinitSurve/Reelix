"""Provider-independent media downloader abstraction and yt-dlp implementation."""

from __future__ import annotations

import abc
import enum
import hashlib
import logging
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from ..models import MediaItem, PostMediaType, compute_file_sha256

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
    post_type: PostMediaType = PostMediaType.REEL
    media_items: List[MediaItem] = field(default_factory=list)
    local_media_paths: List[Path] = field(default_factory=list)
    total_size_bytes: int = 0

    @property
    def is_success(self) -> bool:
        if self.status != DownloadStatus.SUCCESS:
            return False
        if self.media_path is not None and self.media_path.exists() and self.media_path.stat().st_size > 0:
            return True
        if self.local_media_paths and all(p.exists() and p.stat().st_size > 0 for p in self.local_media_paths):
            return True
        return False


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

    @staticmethod
    def _validate_media_file(file_path: Path, expected_type: str) -> bool:
        """Validates file existence, non-zero size, and magic byte signatures."""
        if not file_path.exists() or file_path.stat().st_size == 0:
            return False
        try:
            with open(file_path, "rb") as f:
                header = f.read(32)
            if expected_type == "image":
                # JPEG (\xff\xd8\xff), PNG (\x89PNG), WEBP (RIFF....WEBP)
                if header.startswith(b"\xff\xd8\xff"):
                    return True
                if header.startswith(b"\x89PNG\r\n\x1a\n"):
                    return True
                if header.startswith(b"RIFF") and b"WEBP" in header:
                    return True
                return False
            elif expected_type == "video":
                # MP4 / QuickTime / ISO media container signatures
                if len(header) >= 8 and header[4:8] in (b"ftyp", b"moov", b"mdat", b"wide", b"skip"):
                    return True
                # Matroska / WebM
                if header.startswith(b"\x1a\x45\xdf\xa3"):
                    return True
                return len(header) > 16
            return True
        except Exception:
            return False

    def _download_stream(self, media_url: str, dest_path: Path, expected_type: str) -> bool:
        """Downloads a single direct media stream with headers and integrity verification."""
        req = urllib.request.Request(
            media_url,
            headers={
                "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer": "https://www.instagram.com/",
                "Accept": "*/*",
            },
        )
        temp_dest = dest_path.with_suffix(dest_path.suffix + ".part")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_seconds) as resp, open(temp_dest, "wb") as f:
                while chunk := resp.read(65536):
                    f.write(chunk)
            if self._validate_media_file(temp_dest, expected_type):
                if dest_path.exists():
                    dest_path.unlink()
                temp_dest.rename(dest_path)
                return True
            else:
                if temp_dest.exists():
                    temp_dest.unlink()
                return False
        except Exception as e:
            logger.warning("Failed streaming media from %s: %s", media_url[:80], e)
            if temp_dest.exists():
                temp_dest.unlink()
            return False

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

        meta_opts: Dict[str, Any] = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "socket_timeout": self.timeout_seconds,
        }

        ydl_opts: Dict[str, Any] = {
            "outtmpl": outtmpl,
            "format": "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
            "merge_output_format": "mp4",
            "noplaylist": False,
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
                info = None

                try:
                    with self._get_ydl_instance(ydl_opts) as ydl:
                        info = ydl.extract_info(url, download=True)
                except Exception as dl_ex:
                    err_msg = str(dl_ex).lower()
                    if "no video formats found" in err_msg or "unsupported url" not in err_msg and ("carousel" in err_msg or "image" in err_msg or "thumbnails" in err_msg or "no video" in err_msg):
                        logger.info("Standard video runner found no video formats; querying raw metadata for images/carousel: %s", dl_ex)
                        try:
                            unprocessed_opts = dict(meta_opts)
                            unprocessed_opts["process"] = False
                            with self._get_ydl_instance(unprocessed_opts) as ydl:
                                info = ydl.extract_info(url, download=False, process=False)
                        except Exception as unproc_ex:
                            raise dl_ex from unproc_ex
                    else:
                        raise dl_ex

                if not info or not isinstance(info, dict):
                    last_error = f"yt-dlp returned empty or invalid info: {type(info)}"
                    last_status = DownloadStatus.UNAVAILABLE
                    is_retryable = False
                    break

                caption = str(info.get("description") or info.get("title") or "") or None
                title = str(info.get("title")) if info.get("title") else None
                thumbnail_url = str(info.get("thumbnail")) if info.get("thumbnail") else None
                entries = info.get("entries")

                # ===============================================================
                # CASE 1: Carousel / Multi-item post
                # ===============================================================
                if entries and isinstance(entries, list) and len(entries) > 1:
                    logger.info("Detected multi-item carousel with %d items for %s", len(entries), item_id)
                    media_items: List[MediaItem] = []
                    local_paths: List[Path] = []
                    total_bytes = 0

                    for idx, entry in enumerate(entries):
                        if not isinstance(entry, dict):
                            continue
                        formats = entry.get("formats") or []
                        v_formats = [
                            f for f in formats
                            if f.get("url") and (f.get("vcodec") != "none" or f.get("ext") in ("mp4", "webm") or f.get("video_ext") == "mp4")
                        ]
                        dur_sec = entry.get("duration")
                        dur_ms = int(dur_sec * 1000) if isinstance(dur_sec, (int, float)) else None

                        if v_formats:
                            m_type = "VIDEO"
                            ext = "mp4"
                            mime_type = "video/mp4"
                            best_video = max(v_formats, key=lambda f: (f.get("height") or 0, f.get("tbr") or 0))
                            media_url = best_video["url"]
                            width = best_video.get("width")
                            height = best_video.get("height")
                        else:
                            m_type = "IMAGE"
                            ext = "jpg"
                            mime_type = "image/jpeg"
                            thumbs = entry.get("thumbnails") or []
                            best_thumb = thumbs[-1] if thumbs else None
                            media_url = best_thumb["url"] if best_thumb else entry.get("url")
                            width = (best_thumb.get("width") if best_thumb else None) or entry.get("width")
                            height = (best_thumb.get("height") if best_thumb else None) or entry.get("height")

                        file_name = f"{item_id}_{idx + 1}.{ext}"
                        target_path = output_dir / file_name

                        # Check if file exists or download stream
                        download_success = False
                        if target_path.exists() and target_path.stat().st_size > 0:
                            download_success = True
                        elif media_url and (media_url.startswith("http://") or media_url.startswith("https://")):
                            download_success = self._download_stream(media_url, target_path, m_type.lower())

                        if download_success and target_path.exists():
                            f_size = target_path.stat().st_size
                            c_hash = compute_file_sha256(target_path)
                            total_bytes += f_size
                            local_paths.append(target_path)
                            media_items.append(
                                MediaItem(
                                    index=idx,
                                    media_type=m_type,
                                    file_name=file_name,
                                    mime_type=mime_type,
                                    size_bytes=f_size,
                                    width=width,
                                    height=height,
                                    duration_ms=dur_ms,
                                    content_hash=c_hash,
                                    download_status="COMPLETED",
                                    original_url=media_url,
                                )
                            )
                        else:
                            logger.warning("Failed downloading item %d (%s) for carousel %s", idx + 1, m_type, item_id)

                    if not media_items:
                        last_error = f"Failed to acquire any carousel items for {item_id}"
                        last_status = DownloadStatus.UNAVAILABLE
                        is_retryable = False
                        break

                    has_videos = any(m.media_type == "VIDEO" for m in media_items)
                    has_images = any(m.media_type == "IMAGE" for m in media_items)
                    if has_videos and has_images:
                        post_type = PostMediaType.MIXED_CAROUSEL
                    else:
                        post_type = PostMediaType.CAROUSEL

                    # Select primary media_path: first video if any (for audio extraction), else first image
                    primary_path = next((p for p in local_paths if p.suffix.lower() == ".mp4"), local_paths[0])
                    primary_item = next((m for m in media_items if m.media_type == "VIDEO"), media_items[0])

                    return DownloadResult(
                        status=DownloadStatus.SUCCESS,
                        media_path=primary_path,
                        mime_type=primary_item.mime_type,
                        file_name=primary_path.name,
                        size_bytes=primary_item.size_bytes,
                        duration_ms=primary_item.duration_ms,
                        title=title,
                        caption=caption,
                        thumbnail_url=thumbnail_url,
                        post_type=post_type,
                        media_items=media_items,
                        local_media_paths=local_paths,
                        total_size_bytes=total_bytes,
                        provider="yt-dlp",
                    )

                # ===============================================================
                # CASE 2: Single Image Post
                # ===============================================================
                formats = info.get("formats") or []
                v_formats = [
                    f for f in formats
                    if f.get("url") and (f.get("vcodec") != "none" or f.get("ext") in ("mp4", "webm") or f.get("video_ext") == "mp4")
                ]
                is_single_image = not v_formats and info.get("vcodec") in (None, "none") and bool(info.get("thumbnails"))

                if is_single_image:
                    logger.info("Detected single-image post for %s", item_id)
                    thumbs = info.get("thumbnails") or []
                    best_thumb = thumbs[-1] if thumbs else None
                    media_url = best_thumb["url"] if best_thumb else info.get("url")
                    width = (best_thumb.get("width") if best_thumb else None) or info.get("width")
                    height = (best_thumb.get("height") if best_thumb else None) or info.get("height")

                    file_name = f"{item_id}_1.jpg"
                    img_path = output_dir / file_name

                    dl_ok = False
                    if img_path.exists() and img_path.stat().st_size > 0:
                        dl_ok = True
                    elif media_url:
                        dl_ok = self._download_stream(media_url, img_path, "image")

                    if not dl_ok or not img_path.exists() or img_path.stat().st_size == 0:
                        last_error = f"Failed to acquire single image for {item_id}"
                        last_status = DownloadStatus.UNAVAILABLE
                        is_retryable = False
                        break

                    f_size = img_path.stat().st_size
                    c_hash = compute_file_sha256(img_path)
                    item_obj = MediaItem(
                        index=0,
                        media_type="IMAGE",
                        file_name=file_name,
                        mime_type="image/jpeg",
                        size_bytes=f_size,
                        width=width,
                        height=height,
                        duration_ms=None,
                        content_hash=c_hash,
                        download_status="COMPLETED",
                        original_url=media_url,
                    )

                    return DownloadResult(
                        status=DownloadStatus.SUCCESS,
                        media_path=img_path,
                        mime_type="image/jpeg",
                        file_name=file_name,
                        size_bytes=f_size,
                        duration_ms=None,
                        title=title,
                        caption=caption,
                        thumbnail_url=thumbnail_url,
                        post_type=PostMediaType.IMAGE_POST,
                        media_items=[item_obj],
                        local_media_paths=[img_path],
                        total_size_bytes=f_size,
                        provider="yt-dlp",
                    )

                # ===============================================================
                # CASE 3: Single Video Reel / Video Post
                # ===============================================================
                # Locate or download the video file
                media_path = self._find_downloaded_file(output_dir, item_id, info)
                if not media_path or not media_path.exists() or media_path.stat().st_size == 0:
                    # If not yet downloaded, invoke download runner now
                    with self._get_ydl_instance(ydl_opts) as ydl:
                        dl_info = ydl.extract_info(url, download=True)
                        if dl_info and isinstance(dl_info, dict):
                            info = dl_info
                    media_path = self._find_downloaded_file(output_dir, item_id, info)

                if not media_path or not media_path.exists() or media_path.stat().st_size == 0:
                    last_error = f"Downloaded media file not found or empty in {output_dir}"
                    last_status = DownloadStatus.UNAVAILABLE
                    is_retryable = False
                    break

                file_size = media_path.stat().st_size
                duration_sec = info.get("duration")
                duration_ms = int(duration_sec * 1000) if isinstance(duration_sec, (int, float)) else None
                title = str(info["title"]) if info.get("title") else None
                caption = str(info["description"]) if info.get("description") else caption
                thumbnail_url = str(info["thumbnail"]) if info.get("thumbnail") else thumbnail_url

                post_type = PostMediaType.REEL if "/reel" in url.lower() else PostMediaType.VIDEO_POST
                c_hash = compute_file_sha256(media_path)
                item_obj = MediaItem(
                    index=0,
                    media_type="VIDEO",
                    file_name=media_path.name,
                    mime_type="video/mp4",
                    size_bytes=file_size,
                    duration_ms=duration_ms,
                    content_hash=c_hash,
                    download_status="COMPLETED",
                )

                logger.info(
                    "yt-dlp successfully downloaded video for item %s: %s (%d bytes)",
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
                    post_type=post_type,
                    media_items=[item_obj],
                    local_media_paths=[media_path],
                    total_size_bytes=file_size,
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
