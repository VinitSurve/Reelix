"""Gemini AI structured knowledge extraction for Reelix."""

from __future__ import annotations

import email.utils
import json
import logging
import random
import re
import socket
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional
import urllib.request
import urllib.error

from ..config import ALLOWED_GEMINI_MODELS, DEFAULT_GEMINI_MODEL
from ..models import GeminiAnalysisResult

logger = logging.getLogger(__name__)

GEMINI_API_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

SYSTEM_INSTRUCTION = """You are Reelix, an intelligent knowledge extraction system for personal saved content.
Analyze the provided transcript, on-screen visual text (OCR), caption, personal notes, and metadata. Generate structured, actionable knowledge.

Return ONLY a valid JSON object with EXACTLY the following 9 keys:
{
  "summary": "Concise 1-2 sentence high-level summary",
  "detailed_summary": "Thorough 1-3 paragraph comprehensive breakdown of the content and its core arguments",
  "main_topic": "Primary subject matter (2-6 words)",
  "category": "Broad classification (e.g., Software Engineering, Cooking, Finance, Fitness)",
  "keywords": ["keyword1", "keyword2", "keyword3"],
  "tags": ["tag1", "tag2", "tag3"],
  "key_takeaways": [
    "Key takeaway point 1",
    "Key takeaway point 2"
  ],
  "important_concepts": [
    "Important concept or term 1",
    "Important concept or term 2"
  ],
  "actionable_steps": [
    "Actionable step or recommendation 1",
    "Actionable step or recommendation 2"
  ]
}

Rules:
- Never fabricate information not present or directly implied in the source text.
- If no transcript or detailed text is provided, summarize available title/metadata/OCR text honestly.
- Output MUST be valid JSON only without markdown wrapping or preamble.
"""


@dataclass
class GeminiAnalysisOutcome:
    success: bool
    result: Optional[GeminiAnalysisResult] = None
    error: Optional[str] = None
    is_retryable: bool = False
    retry_after_seconds: Optional[float] = None


class GeminiAnalyzer:
    """Interacts with Google Gemini free-tier API to extract structured knowledge."""

    def __init__(
        self,
        api_key: Optional[str],
        model: str = DEFAULT_GEMINI_MODEL,
        max_retries: int = 3,
        base_delay_seconds: float = 1.0,
        max_delay_seconds: float = 30.0,
        request_timeout_seconds: int = 30,
    ):
        if model not in ALLOWED_GEMINI_MODELS:
            raise ValueError(
                f"Disallowed Gemini model '{model}'. Reelix is strictly restricted to "
                f"the following Google AI Studio free-tier models: {ALLOWED_GEMINI_MODELS}"
            )
        self.api_key = api_key
        self.model = model
        self.max_retries = max_retries
        self.base_delay_seconds = base_delay_seconds
        self.max_delay_seconds = max_delay_seconds
        self.request_timeout_seconds = request_timeout_seconds

    def _parse_retry_after(self, headers: Any) -> Optional[float]:
        if not headers:
            return None
        val = headers.get("Retry-After")
        if not val:
            return None
        try:
            return max(0.0, float(val.strip()))
        except ValueError:
            pass
        try:
            parsed = email.utils.parsedate_to_datetime(val.strip())
            delay = parsed.timestamp() - time.time()
            return max(0.0, delay)
        except Exception:
            return None

    def _compute_backoff_delay(self, attempt: int) -> float:
        backoff = self.base_delay_seconds * (2 ** (attempt - 1))
        jitter = 0.8 + 0.4 * random.random()
        return min(self.max_delay_seconds, backoff * jitter)

    def analyze(
        self,
        title: Optional[str] = None,
        caption: Optional[str] = None,
        transcript: Optional[str] = None,
        notes: Optional[str] = None,
        ocr_text: Optional[str] = None,
        category: Optional[str] = None,
        user_tags: Optional[List[str]] = None,
    ) -> GeminiAnalysisOutcome:
        if not self.api_key:
            return GeminiAnalysisOutcome(
                success=False,
                error="GEMINI_API_KEY is not configured",
                is_retryable=False,
            )

        content_parts: List[str] = []
        if title:
            content_parts.append(f"Title: {title}")
        if category:
            content_parts.append(f"User-specified Category: {category}")
        if user_tags:
            content_parts.append(f"User-specified Tags: {', '.join(user_tags)}")
        if caption:
            content_parts.append(f"Caption: {caption}")
        if notes:
            content_parts.append(f"Personal notes: {notes}")
        if ocr_text:
            content_parts.append(f"On-Screen Visual Text (OCR):\n{ocr_text}")
        if transcript:
            content_parts.append(f"Audio Transcript:\n{transcript}")

        combined_text = "\n\n".join(content_parts).strip()
        if not combined_text:
            return GeminiAnalysisOutcome(
                success=False,
                error="No text (transcript, caption, notes, or OCR text) available for analysis",
                is_retryable=False,
            )

        url = f"{GEMINI_API_ENDPOINT.format(model=self.model)}?key={self.api_key}"

        payload = {
            "contents": [
                {
                    "parts": [
                        {"text": combined_text}
                    ]
                }
            ],
            "systemInstruction": {
                "parts": [
                    {"text": SYSTEM_INSTRUCTION}
                ]
            },
            "generationConfig": {
                "temperature": 0.2,
                "response_mime_type": "application/json",
            },
        }

        req_data = json.dumps(payload).encode("utf-8")

        for attempt in range(1, self.max_retries + 1):
            try:
                req = urllib.request.Request(
                    url,
                    data=req_data,
                    headers={"Content-Type": "application/json"},
                    method="POST",
                )

                start_t = time.time()
                logger.info("Calling Gemini API (%s) attempt %d/%d...", self.model, attempt, self.max_retries)
                with urllib.request.urlopen(req, timeout=self.request_timeout_seconds) as resp:
                    resp_bytes = resp.read()
                    elapsed = time.time() - start_t
                    logger.info("Gemini API responded in %.2fs", elapsed)

                resp_json = json.loads(resp_bytes.decode("utf-8"))
                parsed_result = self._parse_gemini_response(resp_json)
                if parsed_result is None:
                    if attempt < self.max_retries:
                        logger.warning("Failed to parse structured JSON from Gemini response on attempt %d; retrying", attempt)
                        time.sleep(self.base_delay_seconds)
                        continue
                    return GeminiAnalysisOutcome(
                        success=False,
                        error="Failed to parse structured JSON from Gemini response",
                        is_retryable=False,
                    )

                return GeminiAnalysisOutcome(success=True, result=parsed_result)

            except urllib.error.HTTPError as e:
                err_body = e.read().decode("utf-8", errors="replace")
                logger.error("Gemini API HTTP %d error on attempt %d/%d: %s", e.code, attempt, self.max_retries, err_body)

                # Permanent authentication or client errors: do not retry
                if e.code in (400, 401, 403, 404):
                    if e.code == 401 or "API_KEY_INVALID" in err_body or "not valid" in err_body.lower():
                        msg = f"Gemini API authentication failed (HTTP {e.code}): invalid or unauthorized API key"
                    elif e.code in (403, 404):
                        msg = f"Gemini API model access error (HTTP {e.code}): model '{self.model}' unavailable or access denied: {err_body}"
                    else:
                        msg = f"Gemini API bad request (HTTP 400): {err_body}"
                    return GeminiAnalysisOutcome(success=False, error=msg, is_retryable=False)

                # Transient rate limit (429) or server errors (500, 502, 503, 504)
                if e.code == 429 or e.code in (500, 502, 503, 504):
                    retry_after = self._parse_retry_after(e.headers)
                    if retry_after is not None and retry_after > 0:
                        delay = min(self.max_delay_seconds, retry_after)
                    else:
                        delay = self._compute_backoff_delay(attempt)

                    if attempt < self.max_retries:
                        logger.warning(
                            "Gemini API transient HTTP %d error on attempt %d/%d; retrying in %.2fs (Retry-After=%s)",
                            e.code, attempt, self.max_retries, delay, retry_after
                        )
                        time.sleep(delay)
                        continue
                    else:
                        return GeminiAnalysisOutcome(
                            success=False,
                            error=f"Gemini API transient HTTP {e.code} error after {attempt} attempts: {err_body}",
                            is_retryable=True,
                            retry_after_seconds=delay,
                        )

                return GeminiAnalysisOutcome(success=False, error=f"Gemini HTTP {e.code}: {err_body}", is_retryable=False)

            except (urllib.error.URLError, TimeoutError, socket.timeout) as e:
                logger.error("Gemini API network/timeout error on attempt %d/%d: %s", attempt, self.max_retries, e)
                delay = self._compute_backoff_delay(attempt)
                if attempt < self.max_retries:
                    logger.warning("Retrying after network/timeout error in %.2fs...", delay)
                    time.sleep(delay)
                    continue
                return GeminiAnalysisOutcome(
                    success=False,
                    error=f"Gemini API network/timeout error after {attempt} attempts: {e}",
                    is_retryable=True,
                    retry_after_seconds=delay,
                )
            except Exception as e:
                logger.error("Unexpected error calling Gemini API: %s", e)
                return GeminiAnalysisOutcome(success=False, error=f"Gemini API error: {e}", is_retryable=False)

    def _parse_gemini_response(self, resp_json: Dict[str, Any]) -> Optional[GeminiAnalysisResult]:
        try:
            candidates = resp_json.get("candidates", [])
            if not candidates:
                return None
            parts = candidates[0].get("content", {}).get("parts", [])
            if not parts:
                return None
            raw_text = parts[0].get("text", "").strip()
            if not raw_text:
                return None

            # Clean markdown code blocks if present
            cleaned_text = re.sub(r"^```(?:json)?\s*", "", raw_text)
            cleaned_text = re.sub(r"\s*```$", "", cleaned_text).strip()

            data = json.loads(cleaned_text)
            if not isinstance(data, dict):
                return None

            summary = str(data.get("summary", "")).strip()
            detailed_summary = str(data.get("detailed_summary", "")).strip()
            key_takeaways = self._clean_list(data.get("key_takeaways"))

            # Honest completeness check: reject empty analysis
            if not summary and not detailed_summary and not key_takeaways:
                logger.warning("Gemini returned empty or blank analysis fields")
                return None

            return GeminiAnalysisResult(
                summary=summary,
                detailed_summary=detailed_summary,
                main_topic=str(data.get("main_topic", "")).strip(),
                category=str(data.get("category", "General")).strip(),
                keywords=self._clean_list(data.get("keywords")),
                tags=self._clean_list(data.get("tags")),
                key_takeaways=key_takeaways,
                important_concepts=self._clean_list(data.get("important_concepts")),
                actionable_steps=self._clean_list(data.get("actionable_steps")),
                model_name=self.model,
                generated_at=int(time.time() * 1000),
            )
        except Exception as e:
            logger.warning("Error deserializing Gemini JSON: %s", e)
            return None

    @staticmethod
    def _clean_list(val: Any) -> List[str]:
        if isinstance(val, list):
            return [str(item).strip() for item in val if str(item).strip()]
        if isinstance(val, str) and val.strip():
            return [line.strip() for line in val.split("\n") if line.strip()]
        return []

