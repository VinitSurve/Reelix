"""Unit tests for FFmpeg audio extraction stage."""

import subprocess
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from workers.processor.src.stages.audio_extractor import FFmpegAudioExtractor


class TestAudioExtractor(unittest.TestCase):

    def setUp(self):
        self.extractor = FFmpegAudioExtractor(ffmpeg_bin="ffmpeg")
        self.test_dir = Path("/tmp/test_audio_extractor")
        self.test_dir.mkdir(parents=True, exist_ok=True)
        self.input_file = self.test_dir / "sample.mp4"
        self.input_file.write_bytes(b"dummy video data")
        self.output_wav = self.test_dir / "sample.wav"

    def tearDown(self):
        if self.test_dir.exists():
            import shutil
            shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_missing_input_file_returns_error(self):
        non_existent = self.test_dir / "not_there.mp4"
        res = self.extractor.extract(non_existent, self.output_wav)
        self.assertFalse(res.success)
        self.assertIn("does not exist", res.error)

    @patch("shutil.which")
    def test_missing_ffmpeg_binary_returns_error(self, mock_which):
        mock_which.return_value = None
        res = self.extractor.extract(self.input_file, self.output_wav)
        self.assertFalse(res.success)
        self.assertIn("not installed", res.error)

    @patch("shutil.which")
    @patch("subprocess.run")
    def test_successful_extraction(self, mock_run, mock_which):
        mock_which.return_value = "/usr/bin/ffmpeg"

        def side_effect(cmd, **kwargs):
            self.output_wav.write_bytes(b"RIFF dummy wav header and pcm samples")
            return MagicMock(returncode=0, stderr="")

        mock_run.side_effect = side_effect

        res = self.extractor.extract(self.input_file, self.output_wav)
        self.assertTrue(res.success)
        self.assertEqual(self.output_wav, res.wav_path)
        self.assertGreater(res.size_bytes, 0)
        self.assertFalse(res.no_audio_track)

    @patch("shutil.which")
    @patch("subprocess.run")
    def test_no_audio_track_detected(self, mock_run, mock_which):
        mock_which.return_value = "/usr/bin/ffmpeg"
        mock_run.return_value = MagicMock(
            returncode=1,
            stderr="Output file does not contain any stream",
        )

        res = self.extractor.extract(self.input_file, self.output_wav)
        self.assertFalse(res.success)
        self.assertTrue(res.no_audio_track)
        self.assertIn("No audio track", res.error)

    @patch("shutil.which")
    @patch("subprocess.run")
    def test_ffmpeg_timeout(self, mock_run, mock_which):
        mock_which.return_value = "/usr/bin/ffmpeg"
        mock_run.side_effect = subprocess.TimeoutExpired(cmd=["ffmpeg"], timeout=5)

        res = self.extractor.extract(self.input_file, self.output_wav, timeout_seconds=5)
        self.assertFalse(res.success)
        self.assertIn("timed out", res.error)


if __name__ == "__main__":
    unittest.main()

