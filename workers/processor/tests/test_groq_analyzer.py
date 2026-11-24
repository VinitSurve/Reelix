"""Tests for Groq AI structured extraction, key rotation, and Gemini failover."""

import io
import json
import unittest
import urllib.error
from unittest.mock import MagicMock, patch

from workers.processor.src.models import GeminiAnalysisResult
from workers.processor.src.stages.gemini_analyzer import GeminiAnalyzer, GeminiAnalysisOutcome
from workers.processor.src.stages.groq_analyzer import (
    GroqAnalyzer,
    GroqKeyManager,
    mask_key,
)


class TestGroqKeyManager(unittest.TestCase):
    def test_mask_key_security(self):
        self.assertEqual(mask_key(""), "<none>")
        self.assertEqual(mask_key("short"), "***")
        masked = mask_key("gsk_1234567890abcdef")
        self.assertTrue(masked.startswith("gsk_"))
        self.assertTrue(masked.endswith("cdef"))
        self.assertNotIn("1234567890", masked)

    def test_key_rotation_on_rate_limit(self):
        km = GroqKeyManager(["key-1", "key-2", "key-3"])
        self.assertEqual(km.total_keys, 3)

        idx1, k1 = km.get_active_key()
        self.assertEqual(idx1, 1)
        self.assertEqual(k1, "key-1")

        km.mark_rate_limited(1, retry_after=30.0)

        idx2, k2 = km.get_active_key()
        self.assertEqual(idx2, 2)
        self.assertEqual(k2, "key-2")

        km.mark_rate_limited(2, retry_after=30.0)

        idx3, k3 = km.get_active_key()
        self.assertEqual(idx3, 3)
        self.assertEqual(k3, "key-3")

    def test_permanent_auth_failure_disables_key(self):
        km = GroqKeyManager(["key-bad", "key-good"])
        km.mark_permanent_failure(1, "401 Unauthorized")

        idx, k = km.get_active_key()
        self.assertEqual(idx, 2)
        self.assertEqual(k, "key-good")

        km.mark_permanent_failure(2, "403 Forbidden")
        self.assertFalse(km.has_usable_keys())
        self.assertIsNone(km.get_active_key())


class TestGroqAnalyzer(unittest.TestCase):
    def setUp(self):
        self.sample_groq_payload = {
            "choices": [
                {
                    "message": {
                        "content": json.dumps({
                            "summary": "AI tools for developers.",
                            "detailed_summary": "Thorough breakdown of modern developer productivity tools.",
                            "main_topic": "Developer Productivity",
                            "category": "Technology",
                            "keywords": ["ai", "devtools", "productivity"],
                            "tags": ["coding", "automation"],
                            "key_takeaways": ["Use AI to accelerate development", "Maintain clean codebases"],
                            "important_concepts": ["Agentic AI", "Code Review"],
                            "actionable_steps": ["Audit workflows", "Integrate automated checks"],
                        })
                    }
                }
            ]
        }

    @patch("urllib.request.urlopen")
    def test_successful_groq_analysis(self, mock_urlopen):
        resp_bytes = json.dumps(self.sample_groq_payload).encode("utf-8")
        mock_resp = MagicMock()
        mock_resp.read.return_value = resp_bytes
        mock_resp.__enter__.return_value = mock_resp
        mock_urlopen.return_value = mock_resp

        analyzer = GroqAnalyzer(api_keys=["key-1", "key-2"])
        outcome = analyzer.analyze(transcript="Today we talk about AI tools for software engineers.")

        self.assertTrue(outcome.success)
        self.assertIsNotNone(outcome.result)
        self.assertEqual(outcome.result.summary, "AI tools for developers.")
        self.assertEqual(outcome.result.main_topic, "Developer Productivity")
        self.assertEqual(outcome.result.category, "Technology")
        self.assertEqual(len(outcome.result.key_takeaways), 2)
        self.assertIn("groq", outcome.result.model_name)

    @patch("urllib.request.urlopen")
    def test_key_rotation_on_429(self, mock_urlopen):
        err_429 = urllib.error.HTTPError(
            url="https://api.groq.com/openai/v1/chat/completions",
            code=429,
            msg="Too Many Requests",
            hdrs={"Retry-After": "5"},
            fp=io.BytesIO(b'{"error": {"message": "Rate limit exceeded"}}'),
        )
        resp_bytes = json.dumps(self.sample_groq_payload).encode("utf-8")
        mock_success = MagicMock()
        mock_success.read.return_value = resp_bytes
        mock_success.__enter__.return_value = mock_success

        mock_urlopen.side_effect = [err_429, mock_success]

        analyzer = GroqAnalyzer(api_keys=["key-1", "key-2"])
        outcome = analyzer.analyze(transcript="Testing key rotation")

        self.assertTrue(outcome.success)
        self.assertEqual(mock_urlopen.call_count, 2)
        # Verify key-1 is now rate-limited
        self.assertTrue(analyzer.key_manager.key_states[0].is_rate_limited())


class TestGeminiGroqFailover(unittest.TestCase):
    def setUp(self):
        self.sample_payload = {
            "choices": [
                {
                    "message": {
                        "content": json.dumps({
                            "summary": "Failover summary",
                            "detailed_summary": "Detailed failover explanation",
                            "main_topic": "Failover",
                            "category": "Reliability",
                            "keywords": ["resilience"],
                            "tags": ["cloud"],
                            "key_takeaways": ["Always have a fallback"],
                            "important_concepts": ["High Availability"],
                            "actionable_steps": ["Configure secondary provider"],
                        })
                    }
                }
            ]
        }

    @patch("urllib.request.urlopen")
    def test_gemini_503_fails_over_to_groq(self, mock_urlopen):
        err_503 = urllib.error.HTTPError(
            url="https://generativelanguage.googleapis.com",
            code=503,
            msg="Service Unavailable",
            hdrs={},
            fp=io.BytesIO(b'{"error": "This model is currently experiencing high demand"}'),
        )
        groq_resp_bytes = json.dumps(self.sample_payload).encode("utf-8")
        mock_groq = MagicMock()
        mock_groq.read.return_value = groq_resp_bytes
        mock_groq.__enter__.return_value = mock_groq

        # Gemini retries 5 times with 503, then fails over to Groq which succeeds
        mock_urlopen.side_effect = [err_503, err_503, err_503, err_503, err_503, mock_groq]

        gemini_analyzer = GeminiAnalyzer(
            api_key="gemini-key",
            groq_api_keys=["groq-key-1"],
            base_delay_seconds=0.01,
        )
        outcome = gemini_analyzer.analyze(transcript="High demand test")

        self.assertTrue(outcome.success)
        self.assertIn("groq", outcome.result.model_name)
        self.assertEqual(outcome.result.summary, "Failover summary")

    @patch("urllib.request.urlopen")
    def test_unconfigured_gemini_routes_to_groq(self, mock_urlopen):
        groq_resp_bytes = json.dumps(self.sample_payload).encode("utf-8")
        mock_groq = MagicMock()
        mock_groq.read.return_value = groq_resp_bytes
        mock_groq.__enter__.return_value = mock_groq
        mock_urlopen.return_value = mock_groq

        gemini_analyzer = GeminiAnalyzer(
            api_key=None,
            groq_api_keys=["groq-key-1"],
        )
        outcome = gemini_analyzer.analyze(transcript="No gemini key test")

        self.assertTrue(outcome.success)
        self.assertEqual(mock_urlopen.call_count, 1)
        self.assertIn("groq", outcome.result.model_name)

    @patch("urllib.request.urlopen")
    def test_groq_model_404_fails_over_to_fallback_model(self, mock_urlopen):
        # 1st call to primary model returns 404 model_not_found
        # 2nd call to fallback model succeeds
        err_fp = io.BytesIO(b'{"error":{"message":"The model openai/gpt-oss-120b does not exist or you do not have access to it.","code":"model_not_found"}}')
        http_404 = urllib.error.HTTPError(
            url="https://api.groq.com/openai/v1/chat/completions",
            code=404,
            msg="Not Found",
            hdrs={},
            fp=err_fp,
        )

        success_body = json.dumps({
            "choices": [{
                "message": {
                    "content": json.dumps({
                        "summary": "Fallback summary",
                        "detailed_summary": "Detailed breakdown",
                        "main_topic": "Fallback AI",
                        "category": "Tech",
                        "keywords": ["fallback", "ai"],
                        "tags": ["groq"],
                        "key_takeaways": ["Model failover works"],
                        "important_concepts": ["instant fallback"],
                        "actionable_steps": ["use working model"],
                    })
                }
            }]
        }).encode("utf-8")
        mock_success = MagicMock()
        mock_success.read.return_value = success_body
        mock_success.__enter__.return_value = mock_success

        mock_urlopen.side_effect = [http_404, mock_success]

        analyzer = GroqAnalyzer(api_keys=["gsk-test-key-1"])
        outcome = analyzer.analyze(transcript="Testing model failover")

        self.assertTrue(outcome.success)
        self.assertEqual(outcome.result.summary, "Fallback summary")
        self.assertEqual(mock_urlopen.call_count, 2)
        # Verify second call used fallback model in request body
        second_call_req = mock_urlopen.call_args_list[1][0][0]
        second_payload = json.loads(second_call_req.data.decode("utf-8"))
        self.assertEqual(second_payload["model"], "openai/gpt-oss-20b")
        self.assertIn("openai/gpt-oss-20b", outcome.result.model_name)


if __name__ == "__main__":
    unittest.main()
