"""Unit tests for Gemini AI structured knowledge analysis."""

import json
import unittest
from unittest.mock import MagicMock, patch

from workers.processor.src.stages.gemini_analyzer import GeminiAnalyzer


class TestGeminiAnalyzer(unittest.TestCase):

    def setUp(self):
        self.analyzer = GeminiAnalyzer(api_key="test-api-key", model="gemini-3.6-flash")

    def test_default_model_is_gemini_3_6_flash(self):
        default_analyzer = GeminiAnalyzer(api_key="key")
        self.assertEqual("gemini-3.6-flash", default_analyzer.model)

    def test_model_allowlist_validation(self):
        # Permitted models: gemini-3.6-flash (default) and gemini-3.8-flash (alternative)
        a1 = GeminiAnalyzer(api_key="key", model="gemini-3.6-flash")
        self.assertEqual("gemini-3.6-flash", a1.model)
        a2 = GeminiAnalyzer(api_key="key", model="gemini-3.8-flash")
        self.assertEqual("gemini-3.8-flash", a2.model)

        # Disallowed models must raise ValueError
        for disallowed in ["gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash", "gpt-4"]:
            with self.assertRaises(ValueError):
                GeminiAnalyzer(api_key="key", model=disallowed)

    def test_missing_api_key_returns_error(self):
        no_key = GeminiAnalyzer(api_key=None)
        res = no_key.analyze(title="Test Title")
        self.assertFalse(res.success)
        self.assertIn("not configured", res.error)

    def test_empty_content_returns_error(self):
        res = self.analyzer.analyze()
        self.assertFalse(res.success)
        self.assertIn("No text", res.error)

    @patch("urllib.request.urlopen")
    def test_successful_gemini_response_parsing(self, mock_urlopen):
        expected_json = {
            "summary": "Explains unidirectional data flow in Jetpack Compose.",
            "detailed_summary": "In this guide, the presenter shows how state hoisting keeps composable functions previewable, testable, and maintainable.",
            "main_topic": "Jetpack Compose Architecture",
            "category": "Android Development",
            "keywords": ["compose", "state hoisting", "udf"],
            "tags": ["android", "kotlin", "jetpack"],
            "key_takeaways": [
                "Hoist state above callers",
                "Pass lambdas for user interaction"
            ],
            "important_concepts": [
                "State hoisting",
                "Unidirectional data flow"
            ],
            "actionable_steps": [
                "1. Identify mutable state",
                "2. Move state to caller",
                "3. Expose StateFlow"
            ]
        }

        gemini_api_payload = {
            "candidates": [
                {
                    "content": {
                        "parts": [
                            {
                                # Testing with markdown code fence wrapping
                                "text": f"```json\n{json.dumps(expected_json)}\n```"
                            }
                        ]
                    }
                }
            ]
        }

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(gemini_api_payload).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp
        mock_urlopen.return_value = mock_resp

        res = self.analyzer.analyze(
            title="Compose State Architecture",
            transcript="Welcome back. Today we examine state hoisting.",
        )

        self.assertTrue(res.success)
        self.assertIsNotNone(res.result)
        self.assertEqual("Jetpack Compose Architecture", res.result.main_topic)
        self.assertEqual("Android Development", res.result.category)
        self.assertEqual(["compose", "state hoisting", "udf"], res.result.keywords)
        self.assertEqual(["android", "kotlin", "jetpack"], res.result.tags)
        self.assertEqual(2, len(res.result.key_takeaways))
        self.assertEqual(2, len(res.result.important_concepts))
        self.assertEqual(3, len(res.result.actionable_steps))
        self.assertEqual("gemini-3.6-flash", res.result.model_name)

    @patch("urllib.request.urlopen")
    def test_empty_gemini_response_rejected(self, mock_urlopen):
        # Empty candidate content should fail and not be treated as successful
        gemini_api_payload = {
            "candidates": [
                {
                    "content": {
                        "parts": [
                            {"text": json.dumps({"summary": "", "key_takeaways": []})}
                        ]
                    }
                }
            ]
        }
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(gemini_api_payload).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp
        mock_urlopen.return_value = mock_resp

        res = self.analyzer.analyze(title="Test", transcript="Sample text")
        self.assertFalse(res.success)
        self.assertIn("Failed to parse", res.error)

    @patch("urllib.request.urlopen")
    def test_quota_exceeded_handled_explicitly(self, mock_urlopen):
        import urllib.error
        mock_urlopen.side_effect = urllib.error.HTTPError(
            url="https://generativelanguage.googleapis.com",
            code=429,
            msg="Too Many Requests",
            hdrs={},
            fp=MagicMock(read=lambda: b"Resource has been exhausted (e.g. check quota).")
        )
        res = self.analyzer.analyze(title="Test", transcript="Sample text")
        self.assertFalse(res.success)
        self.assertIn("429", res.error)
        self.assertIn("quota", res.error.lower())


if __name__ == "__main__":
    unittest.main()

