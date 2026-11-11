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
    @patch("time.sleep")
    def test_quota_exceeded_handled_explicitly(self, mock_sleep, mock_urlopen):
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

    @patch("urllib.request.urlopen")
    def test_analyze_with_ocr_and_user_metadata(self, mock_urlopen):
        expected_json = {
            "summary": "Step by step recipe.",
            "detailed_summary": "Details of cooking.",
            "main_topic": "Pasta Recipe",
            "category": "Cooking",
            "keywords": ["pasta", "recipe"],
            "tags": ["food", "italian"],
            "key_takeaways": ["Boil water"],
            "important_concepts": ["Al dente"],
            "actionable_steps": ["1. Boil water"]
        }
        gemini_api_payload = {
            "candidates": [{"content": {"parts": [{"text": json.dumps(expected_json)}]}}]
        }
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(gemini_api_payload).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp
        mock_urlopen.return_value = mock_resp

        res = self.analyzer.analyze(
            title="Pasta Reel",
            notes="Remember to salt water",
            category="Cooking",
            user_tags=["dinner", "quick"],
            ocr_text="Step 1: 500g Pasta\nStep 2: 10 mins",
        )
        self.assertTrue(res.success)
        self.assertEqual("Pasta Recipe", res.result.main_topic)

    @patch("urllib.request.urlopen")
    @patch("time.sleep")
    def test_503_transient_error_retried_and_succeeds(self, mock_sleep, mock_urlopen):
        import urllib.error

        expected_json = {
            "summary": "Recovered after 503.",
            "detailed_summary": "Details.",
            "main_topic": "Recovery",
            "category": "Tech",
            "keywords": ["retry"],
            "tags": ["resilience"],
            "key_takeaways": ["Backoff works"],
            "important_concepts": ["Idempotence"],
            "actionable_steps": ["Retry with jitter"]
        }
        success_payload = {
            "candidates": [{"content": {"parts": [{"text": json.dumps(expected_json)}]}}]
        }
        mock_success = MagicMock()
        mock_success.read.return_value = json.dumps(success_payload).encode("utf-8")
        mock_success.__enter__.return_value = mock_success

        err_503 = urllib.error.HTTPError(
            url="https://generativelanguage.googleapis.com",
            code=503,
            msg="Service Unavailable",
            hdrs={},
            fp=MagicMock(read=lambda: b"This model is currently experiencing high demand.")
        )

        mock_urlopen.side_effect = [err_503, mock_success]

        res = self.analyzer.analyze(title="Retry Test", transcript="Testing retry logic")
        self.assertTrue(res.success)
        self.assertEqual("Recovery", res.result.main_topic)
        self.assertEqual(2, mock_urlopen.call_count)
        mock_sleep.assert_called_once()

    @patch("urllib.request.urlopen")
    @patch("time.sleep")
    def test_429_rate_limit_with_retry_after(self, mock_sleep, mock_urlopen):
        import urllib.error

        expected_json = {
            "summary": "Recovered after 429.",
            "detailed_summary": "Details.",
            "main_topic": "Rate Limit",
            "category": "Networking",
            "keywords": ["429"],
            "tags": ["quota"],
            "key_takeaways": ["Respect Retry-After"],
            "important_concepts": ["Rate limiting"],
            "actionable_steps": ["Wait Retry-After"]
        }
        success_payload = {
            "candidates": [{"content": {"parts": [{"text": json.dumps(expected_json)}]}}]
        }
        mock_success = MagicMock()
        mock_success.read.return_value = json.dumps(success_payload).encode("utf-8")
        mock_success.__enter__.return_value = mock_success

        err_429 = urllib.error.HTTPError(
            url="https://generativelanguage.googleapis.com",
            code=429,
            msg="Too Many Requests",
            hdrs={"Retry-After": "5"},
            fp=MagicMock(read=lambda: b"Resource exhausted")
        )

        mock_urlopen.side_effect = [err_429, mock_success]

        res = self.analyzer.analyze(title="429 Test", transcript="Testing 429 handling")
        self.assertTrue(res.success)
        self.assertEqual("Rate Limit", res.result.main_topic)
        self.assertEqual(2, mock_urlopen.call_count)
        mock_sleep.assert_called_once_with(5.0)

    @patch("urllib.request.urlopen")
    @patch("time.sleep")
    def test_network_timeout_retried_and_succeeds(self, mock_sleep, mock_urlopen):
        expected_json = {
            "summary": "Recovered after timeout.",
            "detailed_summary": "Details.",
            "main_topic": "Timeout Recovery",
            "category": "Networking",
            "keywords": ["timeout"],
            "tags": ["socket"],
            "key_takeaways": ["Socket timeout handled"],
            "important_concepts": ["Timeout"],
            "actionable_steps": ["Retry request"]
        }
        success_payload = {
            "candidates": [{"content": {"parts": [{"text": json.dumps(expected_json)}]}}]
        }
        mock_success = MagicMock()
        mock_success.read.return_value = json.dumps(success_payload).encode("utf-8")
        mock_success.__enter__.return_value = mock_success

        mock_urlopen.side_effect = [TimeoutError("The read operation timed out"), mock_success]

        res = self.analyzer.analyze(title="Timeout Test", transcript="Testing timeout recovery")
        self.assertTrue(res.success)
        self.assertEqual("Timeout Recovery", res.result.main_topic)
        self.assertEqual(2, mock_urlopen.call_count)
        mock_sleep.assert_called_once()

    @patch("urllib.request.urlopen")
    def test_permanent_authentication_failure_aborts_immediately(self, mock_urlopen):
        import urllib.error

        err_401 = urllib.error.HTTPError(
            url="https://generativelanguage.googleapis.com",
            code=401,
            msg="Unauthorized",
            hdrs={},
            fp=MagicMock(read=lambda: b"API_KEY_INVALID: API key not valid.")
        )
        mock_urlopen.side_effect = err_401

        res = self.analyzer.analyze(title="Auth Test", transcript="Testing auth failure")
        self.assertFalse(res.success)
        self.assertFalse(res.is_retryable)
        self.assertIn("authentication failed", res.error.lower())
        self.assertEqual(1, mock_urlopen.call_count)

    @patch("urllib.request.urlopen")
    @patch("time.sleep")
    def test_retry_exhaustion_marks_retryable(self, mock_sleep, mock_urlopen):
        import urllib.error

        err_503 = urllib.error.HTTPError(
            url="https://generativelanguage.googleapis.com",
            code=503,
            msg="Service Unavailable",
            hdrs={},
            fp=MagicMock(read=lambda: b"High demand")
        )
        mock_urlopen.side_effect = err_503

        res = self.analyzer.analyze(title="Exhaustion Test", transcript="Testing exhaustion")
        self.assertFalse(res.success)
        self.assertTrue(res.is_retryable)
        self.assertEqual(3, mock_urlopen.call_count)
        self.assertEqual(2, mock_sleep.call_count)


if __name__ == "__main__":
    unittest.main()

