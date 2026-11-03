"""Unit tests for MediaDownloader abstraction and YtDlpDownloader implementation."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from workers.processor.src.stages.downloader import (
    DownloadResult,
    DownloadStatus,
    MediaDownloader,
    YtDlpDownloader,
)


class TestDownloader(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.output_dir = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def _make_mock_ydl(self) -> MagicMock:
        mock_ydl = MagicMock()
        mock_ydl.__enter__.return_value = mock_ydl
        return mock_ydl

    def test_invalid_url_rejected_immediately(self):
        downloader = YtDlpDownloader()
        res = downloader.download("not-a-valid-url", self.output_dir, "item_123")
        self.assertEqual(res.status, DownloadStatus.UNSUPPORTED_URL)
        self.assertFalse(res.is_retryable)
        self.assertIn("Invalid URL", res.error_message)

    def test_ftp_url_rejected(self):
        downloader = YtDlpDownloader()
        res = downloader.download("ftp://example.com/video.mp4", self.output_dir, "item_123")
        self.assertEqual(res.status, DownloadStatus.UNSUPPORTED_URL)

    def test_successful_download_with_metadata(self):
        mock_ydl = self._make_mock_ydl()
        mock_file = self.output_dir / "item_123_reel.mp4"
        mock_file.write_bytes(b"dummy video content 1234567890")

        mock_info = {
            "title": "Test Reel Title",
            "description": "Great instagram reel caption #testing",
            "duration": 15.5,
            "thumbnail": "https://example.com/thumb.jpg",
            "_filename": str(mock_file),
        }
        mock_ydl.extract_info.return_value = mock_info

        downloader = YtDlpDownloader(ydl_factory=lambda opts: mock_ydl)
        res = downloader.download("https://www.instagram.com/reel/C12345/", self.output_dir, "item_123")

        self.assertEqual(res.status, DownloadStatus.SUCCESS)
        self.assertTrue(res.is_success)
        self.assertEqual(res.media_path, mock_file)
        self.assertEqual(res.size_bytes, len(b"dummy video content 1234567890"))
        self.assertEqual(res.duration_ms, 15500)
        self.assertEqual(res.title, "Test Reel Title")
        self.assertEqual(res.caption, "Great instagram reel caption #testing")
        self.assertEqual(res.thumbnail_url, "https://example.com/thumb.jpg")
        self.assertEqual(res.mime_type, "video/mp4")

    def test_file_discovery_via_directory_scan_fallback(self):
        mock_ydl = self._make_mock_ydl()
        # Create a file matching item prefix in directory, but don't provide _filename in info
        mock_file = self.output_dir / "item_456_vid.mp4"
        mock_file.write_bytes(b"dummy mp4 video bytes")

        mock_info = {
            "title": "Directory Scan Reel",
            "duration": 5.0,
        }
        mock_ydl.extract_info.return_value = mock_info

        downloader = YtDlpDownloader(ydl_factory=lambda opts: mock_ydl)
        res = downloader.download("https://www.instagram.com/p/ABCDEF/", self.output_dir, "item_456")

        self.assertEqual(res.status, DownloadStatus.SUCCESS)
        self.assertEqual(res.media_path, mock_file)
        self.assertEqual(res.duration_ms, 5000)

    def test_error_classification_transient_login_challenge(self):
        downloader = YtDlpDownloader()
        status, retryable = downloader.classify_error("ERROR: Login required to view this post")
        self.assertEqual(status, DownloadStatus.TRANSIENT_FAILURE)
        self.assertTrue(retryable)

    def test_error_classification_permanent_private_post(self):
        downloader = YtDlpDownloader()
        status, retryable = downloader.classify_error("Instagram: User account is private")
        self.assertEqual(status, DownloadStatus.UNAVAILABLE)
        self.assertFalse(retryable)

    def test_error_classification_permanent_post_deleted(self):
        downloader = YtDlpDownloader()
        status, retryable = downloader.classify_error("HTTP Error 404: Post has been removed")
        self.assertEqual(status, DownloadStatus.UNAVAILABLE)
        self.assertFalse(retryable)

    def test_error_classification_unsupported_url(self):
        downloader = YtDlpDownloader()
        status, retryable = downloader.classify_error("ERROR: Unsupported URL: https://unknown.site/page")
        self.assertEqual(status, DownloadStatus.UNSUPPORTED_URL)
        self.assertFalse(retryable)

    def test_error_classification_transient_rate_limit(self):
        downloader = YtDlpDownloader()
        status, retryable = downloader.classify_error("HTTP Error 429: Too Many Requests")
        self.assertEqual(status, DownloadStatus.TRANSIENT_FAILURE)
        self.assertTrue(retryable)

    def test_error_classification_transient_network_timeout(self):
        downloader = YtDlpDownloader()
        status, retryable = downloader.classify_error("Connection timed out after 30 seconds")
        self.assertEqual(status, DownloadStatus.TRANSIENT_FAILURE)
        self.assertTrue(retryable)

    def test_permanent_failure_does_not_retry(self):
        mock_ydl = self._make_mock_ydl()
        mock_ydl.extract_info.side_effect = Exception("Instagram: User account is private")

        downloader = YtDlpDownloader(
            max_retries=3,
            ydl_factory=lambda opts: mock_ydl,
        )
        res = downloader.download("https://www.instagram.com/reel/PrivateReel/", self.output_dir, "item_priv")

        self.assertEqual(res.status, DownloadStatus.UNAVAILABLE)
        self.assertFalse(res.is_retryable)
        self.assertIn("account is private", res.error_message)
        # Should only have been called once (no retries)
        self.assertEqual(mock_ydl.extract_info.call_count, 1)

    @patch("time.sleep")
    def test_transient_failure_retries_with_backoff_up_to_max(self, mock_sleep):
        mock_ydl = self._make_mock_ydl()
        mock_ydl.extract_info.side_effect = Exception("HTTP Error 429: Rate limit exceeded")

        downloader = YtDlpDownloader(
            max_retries=2,
            backoff_base_seconds=1.0,
            backoff_max_seconds=5.0,
            ydl_factory=lambda opts: mock_ydl,
        )
        res = downloader.download("https://www.instagram.com/reel/RateLimited/", self.output_dir, "item_rate")

        self.assertEqual(res.status, DownloadStatus.TRANSIENT_FAILURE)
        self.assertTrue(res.is_retryable)
        # Called 1 initial attempt + 2 retries = 3 attempts
        self.assertEqual(mock_ydl.extract_info.call_count, 3)
        self.assertEqual(mock_sleep.call_count, 2)
        # Check exponential backoff sleep durations (1.0s, 2.0s)
        mock_sleep.assert_any_call(1.0)
        mock_sleep.assert_any_call(2.0)

    @patch("time.sleep")
    def test_transient_failure_recovers_on_retry(self, mock_sleep):
        mock_ydl = self._make_mock_ydl()
        mock_file = self.output_dir / "item_rec_video.mp4"
        mock_file.write_bytes(b"recovered video")

        # Fails once with transient timeout, then succeeds
        mock_ydl.extract_info.side_effect = [
            Exception("Connection timed out"),
            {"_filename": str(mock_file), "duration": 10},
        ]

        downloader = YtDlpDownloader(
            max_retries=2,
            backoff_base_seconds=0.5,
            ydl_factory=lambda opts: mock_ydl,
        )
        res = downloader.download("https://www.instagram.com/reel/RecoverMe/", self.output_dir, "item_rec")

        self.assertEqual(res.status, DownloadStatus.SUCCESS)
        self.assertEqual(mock_ydl.extract_info.call_count, 2)
        self.assertEqual(mock_sleep.call_count, 1)
        mock_sleep.assert_called_with(0.5)

    def test_empty_file_marked_unavailable(self):
        mock_ydl = self._make_mock_ydl()
        mock_file = self.output_dir / "item_empty.mp4"
        mock_file.touch()  # 0 bytes

        mock_ydl.extract_info.return_value = {"_filename": str(mock_file)}

        downloader = YtDlpDownloader(ydl_factory=lambda opts: mock_ydl)
        res = downloader.download("https://www.instagram.com/reel/Empty/", self.output_dir, "item_empty")

        self.assertEqual(res.status, DownloadStatus.UNAVAILABLE)
        self.assertIn("not found or empty", res.error_message)


if __name__ == "__main__":
    unittest.main()
