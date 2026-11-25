"""Unit tests for OCR visual text extraction stage."""

import subprocess
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from workers.processor.src.stages.ocr_extractor import VisualTextExtractor


class TestVisualTextExtractor(unittest.TestCase):

    def setUp(self):
        self.extractor = VisualTextExtractor(ffmpeg_bin="ffmpeg", tesseract_bin="tesseract")
        self.test_dir = Path("/tmp/test_ocr_extractor")
        self.test_dir.mkdir(parents=True, exist_ok=True)
        self.input_video = self.test_dir / "sample.mp4"
        self.input_video.write_bytes(b"dummy video data")

    def tearDown(self):
        if self.test_dir.exists():
            import shutil
            shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_missing_input_file_returns_error(self):
        non_existent = self.test_dir / "not_there.mp4"
        res = self.extractor.extract(non_existent, self.test_dir)
        self.assertFalse(res.success)
        self.assertIn("does not exist", res.error)

    @patch("shutil.which")
    def test_missing_binaries_skips_gracefully(self, mock_which):
        mock_which.return_value = None
        res = self.extractor.extract(self.input_video, self.test_dir)
        self.assertTrue(res.success)
        self.assertEqual(res.text, "")
        self.assertEqual(res.frames_processed, 0)

    @patch("shutil.which")
    @patch("subprocess.run")
    def test_successful_ocr_extraction(self, mock_run, mock_which):
        mock_which.return_value = "/usr/bin/tool"

        def side_effect(cmd, **kwargs):
            # If it's ffmpeg creating frames:
            if "ffmpeg" in cmd[0]:
                frames_dir = self.test_dir / f"ocr_frames_{self.input_video.stem}"
                frames_dir.mkdir(parents=True, exist_ok=True)
                (frames_dir / "frame_01.png").write_bytes(b"dummy frame 1")
                (frames_dir / "frame_02.png").write_bytes(b"dummy frame 2")
                return MagicMock(returncode=0, stderr="")
            # If it's tesseract:
            if "tesseract" in cmd[0]:
                frame_arg = cmd[1]
                if "frame_01" in frame_arg:
                    return MagicMock(returncode=0, stdout="Step 1: Boil Water\n100 C\n")
                else:
                    return MagicMock(returncode=0, stdout="Step 2: Add Pasta\nStep 1: Boil Water\n")
            return MagicMock(returncode=0, stdout="")

        mock_run.side_effect = side_effect

        res = self.extractor.extract(self.input_video, self.test_dir)
        self.assertTrue(res.success)
        self.assertIn("Step 1: Boil Water", res.text)
        self.assertIn("Step 2: Add Pasta", res.text)
        # Deduplication check: Step 1 should only appear once
        self.assertEqual(res.text.count("Step 1: Boil Water"), 1)
        self.assertEqual(res.frames_processed, 2)

    @patch("shutil.which")
    @patch("subprocess.run")
    def test_extract_single_image(self, mock_run, mock_which):
        mock_which.return_value = "/usr/bin/tesseract"
        mock_run.return_value = MagicMock(returncode=0, stdout="Code Repository:\ngithub.com/vinit/repo\n")

        img_file = self.test_dir / "sample_img.jpg"
        img_file.write_bytes(b"\xff\xd8\xff\xe0" + b"\x00" * 50)

        res = self.extractor.extract_image(img_file)
        self.assertTrue(res.success)
        self.assertIn("Code Repository:", res.text)
        self.assertIn("github.com/vinit/repo", res.text)
        self.assertEqual(res.frames_processed, 1)

    @patch("shutil.which")
    @patch("subprocess.run")
    def test_extract_carousel_preserves_slide_order(self, mock_run, mock_which):
        mock_which.return_value = "/usr/bin/tesseract"

        def side_effect(cmd, **kwargs):
            target = cmd[1]
            if "slide_1" in target:
                return MagicMock(returncode=0, stdout="Slide 1 Content: AI Models\n")
            elif "slide_2" in target:
                return MagicMock(returncode=0, stdout="Slide 2 Content: Prompt Engineering\n")
            return MagicMock(returncode=0, stdout="")

        mock_run.side_effect = side_effect

        file1 = self.test_dir / "slide_1.jpg"
        file2 = self.test_dir / "slide_2.jpg"
        file1.write_bytes(b"\xff\xd8\xff\xe0" + b"\x01" * 50)
        file2.write_bytes(b"\xff\xd8\xff\xe0" + b"\x02" * 50)

        from workers.processor.src.models import MediaItem
        items = [
            MediaItem(index=0, media_type="IMAGE", file_name="slide_1.jpg"),
            MediaItem(index=1, media_type="IMAGE", file_name="slide_2.jpg"),
        ]

        res = self.extractor.extract_carousel(items, self.test_dir)
        self.assertTrue(res.success)
        self.assertIn("Slide 1 (Image):", res.text)
        self.assertIn("AI Models", res.text)
        self.assertIn("Slide 2 (Image):", res.text)
        self.assertIn("Prompt Engineering", res.text)
        self.assertTrue(res.text.index("Slide 1") < res.text.index("Slide 2"))
        self.assertEqual(items[0].ocr_text, "Slide 1 Content: AI Models")
        self.assertEqual(items[1].ocr_text, "Slide 2 Content: Prompt Engineering")


if __name__ == "__main__":
    unittest.main()

