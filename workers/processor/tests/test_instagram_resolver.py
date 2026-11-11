"""Unit tests for InstagramPostResolver."""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock

from workers.processor.src.stages.instagram_resolver import (
    CarouselItem,
    InstagramMediaType,
    InstagramPostResolver,
    ResolutionStatus,
)


class TestInstagramResolver(unittest.TestCase):
    def test_extract_shortcode_from_various_formats(self):
        self.assertEqual(
            InstagramPostResolver.extract_shortcode("https://www.instagram.com/p/DfgFcSzlTae/"),
            "DfgFcSzlTae",
        )
        self.assertEqual(
            InstagramPostResolver.extract_shortcode("https://instagram.com/reel/Dfe1veLx4OP/?igsh=abc123xyz"),
            "Dfe1veLx4OP",
        )
        self.assertEqual(
            InstagramPostResolver.extract_shortcode("http://www.instagram.com/reels/DfbNjF_tqQo/?utm_source=ig_web_copy_link"),
            "DfbNjF_tqQo",
        )
        self.assertEqual(
            InstagramPostResolver.extract_shortcode("instagram.com/p/SHORTCODE123"),
            "SHORTCODE123",
        )
        self.assertIsNone(InstagramPostResolver.extract_shortcode("https://youtube.com/watch?v=12345"))
        self.assertIsNone(InstagramPostResolver.extract_shortcode(""))

    def test_canonicalize_url(self):
        self.assertEqual(
            InstagramPostResolver.canonicalize_url("https://www.instagram.com/reel/DfbNjF_tqQo/?igsh=123"),
            "https://www.instagram.com/p/DfbNjF_tqQo/",
        )
        self.assertIsNone(InstagramPostResolver.canonicalize_url("https://invalid.com"))

    def test_resolve_unsupported_url(self):
        resolver = InstagramPostResolver()
        res = resolver.resolve("https://example.com/not-instagram")
        self.assertEqual(res.status, ResolutionStatus.UNSUPPORTED)
        self.assertIn("not a recognized Instagram", res.error_message)

    def test_resolve_single_video_reel(self):
        mock_ydl = MagicMock()
        mock_ydl.__enter__.return_value = mock_ydl
        mock_ydl.extract_info.return_value = {
            "id": "DfgFcSzlTae",
            "title": "Video by nelsonxlee",
            "description": "Cloudflare pointed AI at their code...",
            "uploader": "Nelson Lee",
            "channel": "nelsonxlee",
            "timestamp": 1794022000,
            "vcodec": "avc1.64001f",
            "ext": "mp4",
            "duration": 53.87,
            "width": 1080,
            "height": 1920,
        }
        resolver = InstagramPostResolver(ydl_factory=lambda opts: mock_ydl)
        res = resolver.resolve("https://www.instagram.com/p/DfgFcSzlTae/?igsh=xyz")

        self.assertEqual(res.status, ResolutionStatus.SUCCESS)
        self.assertEqual(res.shortcode, "DfgFcSzlTae")
        self.assertEqual(res.canonical_url, "https://www.instagram.com/p/DfgFcSzlTae/")
        self.assertEqual(res.media_type, InstagramMediaType.VIDEO_REEL)
        self.assertEqual(res.item_count, 1)
        self.assertEqual(res.uploader_name, "Nelson Lee")
        self.assertEqual(res.uploader_handle, "nelsonxlee")
        self.assertEqual(res.duration_ms, 53870)
        self.assertEqual(len(res.items), 1)
        self.assertEqual(res.items[0].media_type, "video")

    def test_resolve_single_image(self):
        mock_ydl = MagicMock()
        mock_ydl.__enter__.return_value = mock_ydl
        mock_ydl.extract_info.return_value = {
            "id": "PhotoShortcode",
            "title": "Photo by photog",
            "description": "Scenic landscape photo",
            "uploader": "Photographer",
            "channel": "photog",
            "timestamp": 1794022000,
            "vcodec": "none",
            "ext": "jpg",
            "duration": None,
            "width": 1080,
            "height": 1080,
        }
        resolver = InstagramPostResolver(ydl_factory=lambda opts: mock_ydl)
        res = resolver.resolve("https://www.instagram.com/p/PhotoShortcode/")

        self.assertEqual(res.status, ResolutionStatus.SUCCESS)
        self.assertEqual(res.media_type, InstagramMediaType.SINGLE_IMAGE)
        self.assertEqual(res.item_count, 1)
        self.assertEqual(res.items[0].media_type, "image")

    def test_resolve_carousel(self):
        mock_ydl = MagicMock()
        mock_ydl.__enter__.return_value = mock_ydl
        mock_ydl.extract_info.return_value = {
            "id": "CarouselShortcode",
            "description": "Multi-item carousel tips",
            "uploader": "CarouselCreator",
            "channel": "creator",
            "entries": [
                {"ext": "jpg", "vcodec": "none", "width": 1080, "height": 1350},
                {"ext": "mp4", "vcodec": "avc1", "width": 1080, "height": 1350, "duration": 15.5},
                {"ext": "jpg", "vcodec": "none", "width": 1080, "height": 1350},
            ],
        }
        resolver = InstagramPostResolver(ydl_factory=lambda opts: mock_ydl)
        res = resolver.resolve("https://www.instagram.com/p/CarouselShortcode/")

        self.assertEqual(res.status, ResolutionStatus.SUCCESS)
        self.assertEqual(res.media_type, InstagramMediaType.CAROUSEL)
        self.assertEqual(res.item_count, 3)
        self.assertEqual(len(res.items), 3)
        self.assertEqual(res.items[0].media_type, "image")
        self.assertEqual(res.items[1].media_type, "video")
        self.assertEqual(res.items[1].duration_ms, 15500)
        self.assertEqual(res.items[2].media_type, "image")

    def test_resolve_private_or_deleted_post(self):
        mock_ydl = MagicMock()
        mock_ydl.__enter__.return_value = mock_ydl
        mock_ydl.extract_info.side_effect = Exception("Instagram: User account is private")
        resolver = InstagramPostResolver(ydl_factory=lambda opts: mock_ydl)
        res = resolver.resolve("https://www.instagram.com/p/PrivatePost/")

        self.assertEqual(res.status, ResolutionStatus.UNAVAILABLE)
        self.assertIn("User account is private", res.error_message)


if __name__ == "__main__":
    unittest.main()

