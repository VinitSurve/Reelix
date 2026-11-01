"""Live integration tests for Google Drive OAuth and Google AI Studio Gemini API.

These tests are skipped by default and only execute when actual external credentials
are provided in the local environment:
- GDRIVE_REFRESH_TOKEN, GDRIVE_CLIENT_ID, GDRIVE_CLIENT_SECRET, GDRIVE_FOLDER_ID
- GEMINI_API_KEY
- LIVE_INTEGRATION_TESTS=1
"""

import os
import unittest

from workers.processor.src.config import WorkerConfig
from workers.processor.src.stages.gemini_analyzer import GeminiAnalyzer
from workers.processor.src.storage.drive_client import GoogleDriveStorageClient


class TestLiveIntegration(unittest.TestCase):

    @unittest.skipUnless(
        os.getenv("LIVE_INTEGRATION_TESTS") == "1" and os.getenv("GEMINI_API_KEY"),
        "Live Gemini API test requires LIVE_INTEGRATION_TESTS=1 and GEMINI_API_KEY",
    )
    def test_live_gemini_generation(self):
        analyzer = GeminiAnalyzer(
            api_key=os.environ["GEMINI_API_KEY"],
            model=os.getenv("GEMINI_MODEL", "gemini-2.5-flash"),
        )
        res = analyzer.analyze(
            title="Clean Architecture in Android",
            transcript="Today we discuss domain layers, use cases, and repositories.",
        )
        self.assertTrue(res.success, f"Live Gemini call failed: {res.error}")
        self.assertIsNotNone(res.result)
        self.assertTrue(len(res.result.summary) > 0)

    @unittest.skipUnless(
        os.getenv("LIVE_INTEGRATION_TESTS") == "1"
        and os.getenv("GDRIVE_REFRESH_TOKEN")
        and os.getenv("GDRIVE_CLIENT_ID")
        and os.getenv("GDRIVE_CLIENT_SECRET")
        and os.getenv("GDRIVE_FOLDER_ID"),
        "Live Google Drive test requires LIVE_INTEGRATION_TESTS=1 and OAuth credentials",
    )
    def test_live_gdrive_oauth_connection(self):
        config = WorkerConfig.from_env()
        client = GoogleDriveStorageClient(config)
        jobs = client.list_queued_jobs(limit=1)
        self.assertIsInstance(jobs, list)


if __name__ == "__main__":
    unittest.main()
