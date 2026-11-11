"""Instagram post resolver for extracting shortcodes, canonicalizing URLs, and classifying content types."""

from __future__ import annotations

import enum
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
from urllib.parse import urlparse

logger = logging.getLogger(__name__)


class InstagramMediaType(str, enum.Enum):
    VIDEO_REEL = "video_reel"
    SINGLE_IMAGE = "single_image"
    CAROUSEL = "carousel"
    UNKNOWN = "unknown"


class ResolutionStatus(str, enum.Enum):
    SUCCESS = "SUCCESS"
    UNAVAILABLE = "UNAVAILABLE"           # Private, deleted, removed
    UNSUPPORTED = "UNSUPPORTED"           # Not an Instagram post/reel URL
    TRANSIENT_FAILURE = "TRANSIENT_FAILURE"  # Network timeout, 429, 5xx


@dataclass
class CarouselItem:
    index: int
    media_type: str  # "image" or "video"
    media_url: Optional[str] = None
    width: Optional[int] = None
    height: Optional[int] = None
    duration_ms: Optional[int] = None


@dataclass
class InstagramResolutionResult:
    status: ResolutionStatus
    shortcode: Optional[str] = None
    canonical_url: Optional[str] = None
    media_type: InstagramMediaType = InstagramMediaType.UNKNOWN
    item_count: int = 1
    items: List[CarouselItem] = field(default_factory=list)
    caption: Optional[str] = None
    uploader_name: Optional[str] = None
    uploader_handle: Optional[str] = None
    upload_timestamp: Optional[int] = None
    duration_ms: Optional[int] = None
    width: Optional[int] = None
    height: Optional[int] = None
    raw_metadata: Dict[str, Any] = field(default_factory=dict)
    error_message: Optional[str] = None


class InstagramPostResolver:
    """Extracts shortcodes, canonicalizes URLs, and queries yt-dlp metadata to classify posts."""

    # Matches /p/<shortcode>, /reel/<shortcode>, /reels/<shortcode>
    INSTAGRAM_URL_PATTERN = re.compile(
        r"(?:https?://)?(?:www\.)?instagram\.com/(?:p|reel|reels)/([A-Za-z0-9_-]+)",
        re.IGNORECASE,
    )

    # Patterns indicating permanent unavailability (private, deleted, removed)
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

    def __init__(self, ydl_factory: Optional[Callable[[Dict[str, Any]], Any]] = None):
        self._ydl_factory = ydl_factory

    @classmethod
    def extract_shortcode(cls, url: str) -> Optional[str]:
        """Extracts the Instagram shortcode from /p/, /reel/, or /reels/ URL."""
        if not url:
            return None
        match = cls.INSTAGRAM_URL_PATTERN.search(url)
        return match.group(1) if match else None

    @classmethod
    def canonicalize_url(cls, url: str) -> Optional[str]:
        """Strips tracking parameters and returns canonical https://www.instagram.com/p/<shortcode>/."""
        shortcode = cls.extract_shortcode(url)
        if not shortcode:
            return None
        return f"https://www.instagram.com/p/{shortcode}/"

    def _get_ydl_instance(self, opts: Dict[str, Any]):
        if self._ydl_factory is not None:
            return self._ydl_factory(opts)
        import yt_dlp
        return yt_dlp.YoutubeDL(opts)

    def resolve(self, url: str) -> InstagramResolutionResult:
        """Resolves an Instagram URL into structured metadata and media classification."""
        shortcode = self.extract_shortcode(url)
        if not shortcode:
            return InstagramResolutionResult(
                status=ResolutionStatus.UNSUPPORTED,
                error_message=f"URL is not a recognized Instagram post or reel: {url}",
            )

        canonical = f"https://www.instagram.com/p/{shortcode}/"
        ydl_opts: Dict[str, Any] = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "extract_flat": False,
        }

        try:
            with self._get_ydl_instance(ydl_opts) as ydl:
                info = ydl.extract_info(canonical, download=False)
                if not info or not isinstance(info, dict):
                    return InstagramResolutionResult(
                        status=ResolutionStatus.UNAVAILABLE,
                        shortcode=shortcode,
                        canonical_url=canonical,
                        error_message="yt-dlp returned empty info for Instagram URL",
                    )
                return self._parse_info(shortcode, canonical, info)
        except Exception as e:
            err_str = str(e)
            for pat in self.PERMANENT_PATTERNS:
                if pat.search(err_str):
                    return InstagramResolutionResult(
                        status=ResolutionStatus.UNAVAILABLE,
                        shortcode=shortcode,
                        canonical_url=canonical,
                        error_message=err_str,
                    )
            return InstagramResolutionResult(
                status=ResolutionStatus.TRANSIENT_FAILURE,
                shortcode=shortcode,
                canonical_url=canonical,
                error_message=err_str,
            )

    def _parse_info(self, shortcode: str, canonical_url: str, info: Dict[str, Any]) -> InstagramResolutionResult:
        entries = info.get("entries")
        caption = info.get("description") or info.get("title")
        uploader_name = info.get("uploader")
        uploader_handle = info.get("channel") or info.get("uploader_id")
        upload_timestamp = info.get("timestamp")

        # 1. Check for Carousel / Multi-item post
        if entries and isinstance(entries, list) and len(entries) > 1:
            items: List[CarouselItem] = []
            for idx, entry in enumerate(entries):
                if not isinstance(entry, dict):
                    continue
                vcodec = entry.get("vcodec")
                ext = entry.get("ext", "")
                is_vid = (vcodec and vcodec != "none") or ext in ("mp4", "webm")
                m_type = "video" if is_vid else "image"
                dur_sec = entry.get("duration")
                dur_ms = int(dur_sec * 1000) if isinstance(dur_sec, (int, float)) else None
                items.append(
                    CarouselItem(
                        index=idx,
                        media_type=m_type,
                        media_url=entry.get("url"),
                        width=entry.get("width"),
                        height=entry.get("height"),
                        duration_ms=dur_ms,
                    )
                )

            return InstagramResolutionResult(
                status=ResolutionStatus.SUCCESS,
                shortcode=shortcode,
                canonical_url=canonical_url,
                media_type=InstagramMediaType.CAROUSEL,
                item_count=len(items),
                items=items,
                caption=caption,
                uploader_name=uploader_name,
                uploader_handle=uploader_handle,
                upload_timestamp=upload_timestamp,
                raw_metadata=info,
            )

        # 2. Check Single Video vs Single Image
        vcodec = info.get("vcodec")
        ext = info.get("ext", "")
        duration_sec = info.get("duration")
        duration_ms = int(duration_sec * 1000) if isinstance(duration_sec, (int, float)) else None
        width = info.get("width")
        height = info.get("height")

        # If entries has exactly 1 entry, use it
        single_entry = entries[0] if (entries and isinstance(entries, list) and len(entries) == 1) else info
        if vcodec is None:
            vcodec = single_entry.get("vcodec")
        if ext == "":
            ext = single_entry.get("ext", "")

        is_video = (vcodec and vcodec != "none") or ext in ("mp4", "webm") or bool(duration_ms)

        m_type = InstagramMediaType.VIDEO_REEL if is_video else InstagramMediaType.SINGLE_IMAGE
        single_item = CarouselItem(
            index=0,
            media_type="video" if is_video else "image",
            media_url=info.get("url"),
            width=width,
            height=height,
            duration_ms=duration_ms,
        )

        return InstagramResolutionResult(
            status=ResolutionStatus.SUCCESS,
            shortcode=shortcode,
            canonical_url=canonical_url,
            media_type=m_type,
            item_count=1,
            items=[single_item],
            caption=caption,
            uploader_name=uploader_name,
            uploader_handle=uploader_handle,
            upload_timestamp=upload_timestamp,
            duration_ms=duration_ms,
            width=width,
            height=height,
            raw_metadata=info,
        )

