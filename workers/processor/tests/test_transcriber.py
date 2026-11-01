"""Unit tests for Whisper speech-to-text transcription."""

import unittest
from pathlib import Path
from unittest.mock import MagicMock

from workers.processor.src.stages.transcriber import WhisperTranscriber


class TestTranscriber(unittest.TestCase):

    def setUp(self):
        self.transcriber = WhisperTranscriber(model_name="base")
        self.test_dir = Path("/tmp/test_transcriber")
        self.test_dir.mkdir(parents=True, exist_ok=True)
        self.wav_file = self.test_dir / "audio.wav"
        self.wav_file.write_bytes(b"RIFF dummy wav audio data")

    def tearDown(self):
        if self.test_dir.exists():
            import shutil
            shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_missing_wav_returns_error(self):
        non_existent = self.test_dir / "not_there.wav"
        res = self.transcriber.transcribe(non_existent)
        self.assertFalse(res.success)
        self.assertIn("does not exist", res.error)

    def test_successful_transcription_with_mocked_model(self):
        mock_model = MagicMock()
        mock_model.transcribe.return_value = {
            "text": "Hello world, this is a test audio transcription.",
            "language": "en",
        }
        self.transcriber._model = mock_model

        res = self.transcriber.transcribe(self.wav_file)
        self.assertTrue(res.success)
        self.assertEqual("Hello world, this is a test audio transcription.", res.transcript)
        self.assertEqual("base", res.model_name)
        self.assertGreater(res.duration_seconds, 0)
        self.assertIsNone(res.error)


if __name__ == "__main__":
    unittest.main()

