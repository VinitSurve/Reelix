"""Unit tests for Gemini AI structured knowledge analysis."""

import json
import unittest
from unittest.mock import MagicMock, patch

from workers.processor.src.stages.gemini_analyzer import GeminiAnalyzer


class TestGeminiAnalyzer(unittest.TestCase):

    def setUp(self):
        self.analyzer = GeminiAnalyzer(api_key="test-api-key", model="gemini-1.5-flash")

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
        self.assertEqual("gemini-1.5-flash", res.result.model_name)


if __name__ == "__main__":
    unittest.main()
