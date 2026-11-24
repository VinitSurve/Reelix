"""Groq AI structured knowledge extraction provider with key rotation and failover for Reelix."""

from __future__ import annotations

import email.utils
import json
import logging
import random
import re
import socket
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
import urllib.error
import urllib.request

from ..models import GeminiAnalysisResult
from .gemini_analyzer import GeminiAnalysisOutcome, SYSTEM_INSTRUCTION

logger = logging.getLogger(__name__)

GROQ_API_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
FALLBACK_GROQ_MODEL = "llama-3.1-8b-instant"
ALLOWED_GROQ_MODELS = (
    "llama-3.3-70b-versatile",
    "llama-3.1-8b-instant",
    "llama-3.1-70b-versatile",
    "mixtral-8x7b-32768",
)


def mask_key(key: Optional[str]) -> str:
    """Safely masks an API key without exposing secrets in logs."""
    if not key:
        return "<none>"
    clean = key.strip()
    if len(clean) <= 8:
        return "***"
    return f"{clean[:4]}...{clean[-4:]}"


@dataclass
class KeyState:
    """Tracks state and rate limit health for a single API key."""
    key_index: int
    key: str
    is_permanently_failed: bool = False
    rate_limited_until: float = 0.0
    failure_count: int = 0
    success_count: int = 0
    last_error: Optional[str] = None

    @property
    def is_usable(self) -> bool:
        return not self.is_permanently_failed

    def is_rate_limited(self, now: Optional[float] = None) -> bool:
        current_time = now if now is not None else time.time()
        return self.is_usable and current_time < self.rate_limited_until


class GroqKeyManager:
    """Manages intelligent selection, rate limiting, and rotation across multiple Groq API keys."""

    def __init__(self, keys: List[str]):
        clean_keys = [k.strip() for k in keys if k and k.strip()]
        self.key_states: List[KeyState] = [
            KeyState(key_index=idx + 1, key=k) for idx, k in enumerate(clean_keys)
        ]
        self._current_ptr: int = 0

    @property
    def total_keys(self) -> int:
        return len(self.key_states)

    def has_usable_keys(self) -> bool:
        return any(ks.is_usable for ks in self.key_states)

    def get_active_key(self, now: Optional[float] = None) -> Optional[Tuple[int, str]]:
        """Returns the next available healthy key (index, key), or None if no keys exist or all permanently failed."""
        if not self.key_states:
            return None

        current_time = now if now is not None else time.time()
        usable = [ks for ks in self.key_states if ks.is_usable]
        if not usable:
            return None

        # 1. Try healthy, non-rate-limited keys starting from current pointer
        n = len(self.key_states)
        for offset in range(n):
            idx = (self._current_ptr + offset) % n
            ks = self.key_states[idx]
            if ks.is_usable and not ks.is_rate_limited(current_time):
                self._current_ptr = (idx + 1) % n
                return (ks.key_index, ks.key)

        # 2. All usable keys are currently rate-limited; pick the one that clears soonest
        earliest = min(usable, key=lambda ks: ks.rate_limited_until)
        return (earliest.key_index, earliest.key)

    def mark_rate_limited(
        self,
        key_index: int,
        retry_after: Optional[float] = None,
        default_delay: float = 10.0,
        error_msg: Optional[str] = None,
    ) -> float:
        """Marks a key rate-limited for a duration derived from Retry-After header or exponential backoff."""
        now = time.time()
        delay = retry_after if retry_after is not None and retry_after > 0 else default_delay
        delay = min(120.0, max(2.0, delay))

        for ks in self.key_states:
            if ks.key_index == key_index:
                ks.failure_count += 1
                ks.rate_limited_until = max(ks.rate_limited_until, now + delay)
                ks.last_error = error_msg
                logger.warning(
                    "Groq API Key #%d (%s) rate-limited for %.1fs (consecutive failures: %d)",
                    key_index,
                    mask_key(ks.key),
                    delay,
                    ks.failure_count,
                )
                return delay
        return delay

    def mark_permanent_failure(self, key_index: int, error_msg: str) -> None:
        """Marks a key permanently disabled (e.g. 401 Unauthorized, 403 Forbidden)."""
        for ks in self.key_states:
            if ks.key_index == key_index:
                ks.is_permanently_failed = True
                ks.last_error = error_msg
                logger.error(
                    "Groq API Key #%d (%s) disabled permanently due to client error: %s",
                    key_index,
                    mask_key(ks.key),
                    error_msg,
                )

    def mark_success(self, key_index: int) -> None:
        """Resets failure count on successful completion."""
        for ks in self.key_states:
            if ks.key_index == key_index:
                ks.failure_count = 0
                ks.success_count += 1
                ks.last_error = None

    def min_wait_seconds(self, now: Optional[float] = None) -> Optional[float]:
        """Returns the minimum seconds to wait before at least one key is no longer rate-limited."""
        current_time = now if now is not None else time.time()
        usable = [ks for ks in self.key_states if ks.is_usable]
        if not usable:
            return None
        wait_times = [max(0.0, ks.rate_limited_until - current_time) for ks in usable]
        return min(wait_times)


class GroqAnalyzer:
    """Interacts with Groq OpenAI-compatible API to extract structured knowledge with multi-key rotation."""

    def __init__(
        self,
        api_keys: List[str],
        model: str = DEFAULT_GROQ_MODEL,
        fallback_model: str = FALLBACK_GROQ_MODEL,
        max_retries_per_key: int = 2,
        request_timeout_seconds: int = 45,
    ):
        if model not in ALLOWED_GROQ_MODELS:
            raise ValueError(
                f"Disallowed Groq model '{model}'. Allowed models: {ALLOWED_GROQ_MODELS}"
            )
        self.key_manager = GroqKeyManager(api_keys)
        self.model = model
        self.fallback_model = fallback_model
        self.max_retries_per_key = max_retries_per_key
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
        if not self.key_manager.has_usable_keys():
            return GeminiAnalysisOutcome(
                success=False,
                error="No valid Groq API keys configured or all keys permanently disabled",
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

        current_model = self.model
        max_total_attempts = max(3, self.key_manager.total_keys * self.max_retries_per_key)
        attempt = 0
        last_error: Optional[str] = None
        min_retry_delay: Optional[float] = None

        payload = {
            "model": current_model,
            "messages": [
                {"role": "system", "content": SYSTEM_INSTRUCTION},
                {"role": "user", "content": combined_text},
            ],
            "temperature": 0.2,
            "response_format": {"type": "json_object"},
        }
        req_data = json.dumps(payload).encode("utf-8")

        while attempt < max_total_attempts and self.key_manager.has_usable_keys():
            attempt += 1
            key_info = self.key_manager.get_active_key()
            if not key_info:
                break
            key_idx, active_key = key_info

            # Check if active key is currently under rate limit
            wait_sec = self.key_manager.min_wait_seconds()
            if wait_sec is not None and wait_sec > 0:
                if wait_sec > 30.0:
                    # Bounded delay: do not block indefinitely in a single synchronous call
                    logger.warning("All Groq keys rate-limited (min wait %.1fs exceeds 30s cap)", wait_sec)
                    return GeminiAnalysisOutcome(
                        success=False,
                        error=f"All Groq keys rate-limited; retry in {int(wait_sec)}s",
                        is_retryable=True,
                        retry_after_seconds=wait_sec,
                    )
                logger.info("Waiting %.2fs for Groq rate limit to expire...", wait_sec)
                time.sleep(wait_sec)

            logger.info(
                "Calling Groq API (%s) using Key #%d (%s) attempt %d/%d...",
                current_model,
                key_idx,
                mask_key(active_key),
                attempt,
                max_total_attempts,
            )

            req = urllib.request.Request(
                GROQ_API_ENDPOINT,
                data=req_data,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {active_key}",
                    "User-Agent": "Reelix-Processor/2.0",
                },
                method="POST",
            )

            try:
                start_t = time.time()
                with urllib.request.urlopen(req, timeout=self.request_timeout_seconds) as resp:
                    resp_bytes = resp.read()
                    elapsed = time.time() - start_t
                    logger.info("Groq API responded in %.2fs", elapsed)

                resp_json = json.loads(resp_bytes.decode("utf-8"))
                parsed_result = self._parse_groq_response(resp_json, model_name=f"groq/{current_model}")
                if parsed_result is not None:
                    self.key_manager.mark_success(key_idx)
                    return GeminiAnalysisOutcome(success=True, result=parsed_result)

                logger.warning("Failed to parse structured JSON from Groq response; rotating key and retrying")
                last_error = "Failed to parse structured JSON from Groq response"

            except urllib.error.HTTPError as e:
                err_body = e.read().decode("utf-8", errors="replace")
                logger.error(
                    "Groq API HTTP %d error on Key #%d: %s",
                    e.code,
                    key_idx,
                    err_body,
                )
                last_error = f"Groq HTTP {e.code}: {err_body}"

                # Permanent authentication or client error on this key: mark key disabled
                if e.code in (401, 403):
                    self.key_manager.mark_permanent_failure(key_idx, f"HTTP {e.code}: {err_body}")
                    continue

                # Model access / not found error (HTTP 404 or model error in body): failover model immediately
                if (e.code in (400, 404) or "model" in err_body.lower()) and current_model == self.model and self.fallback_model:
                    logger.warning(
                        "Groq model '%s' unavailable (HTTP %d: %s); failing over to '%s'",
                        current_model,
                        e.code,
                        err_body,
                        self.fallback_model,
                    )
                    current_model = self.fallback_model
                    payload["model"] = current_model
                    req_data = json.dumps(payload).encode("utf-8")
                    continue

                # Rate limit (429) or server load (503)
                if e.code == 429 or e.code in (500, 502, 503, 504):
                    retry_after = self._parse_retry_after(e.headers)
                    delay = self.key_manager.mark_rate_limited(
                        key_idx,
                        retry_after=retry_after,
                        default_delay=5.0 * (2 ** (attempt - 1)),
                        error_msg=err_body,
                    )
                    min_retry_delay = delay if min_retry_delay is None else min(min_retry_delay, delay)

                    # Model failover if high demand on primary model
                    if e.code == 503 and current_model == self.model and self.fallback_model:
                        logger.warning("Groq model '%s' returned 503; failing over to '%s'", current_model, self.fallback_model)
                        current_model = self.fallback_model
                        payload["model"] = current_model
                        req_data = json.dumps(payload).encode("utf-8")

                    continue

                return GeminiAnalysisOutcome(
                    success=False,
                    error=f"Groq API HTTP {e.code}: {err_body}",
                    is_retryable=False,
                )

            except (urllib.error.URLError, TimeoutError, socket.timeout) as e:
                logger.error("Groq API network/timeout error on Key #%d: %s", key_idx, e)
                last_error = f"Groq network/timeout error: {e}"
                self.key_manager.mark_rate_limited(key_idx, default_delay=3.0, error_msg=str(e))
                continue

            except Exception as e:
                logger.error("Unexpected error calling Groq API: %s", e)
                last_error = f"Unexpected Groq error: {e}"
                continue

        # Exhausted all attempts
        is_retryable = self.key_manager.has_usable_keys()
        return GeminiAnalysisOutcome(
            success=False,
            error=last_error or "Groq API key exhaustion or rate limit limit reached",
            is_retryable=is_retryable,
            retry_after_seconds=min_retry_delay or 15.0,
        )

    def _parse_groq_response(
        self, resp_json: Dict[str, Any], model_name: str
    ) -> Optional[GeminiAnalysisResult]:
        try:
            choices = resp_json.get("choices", [])
            if not choices:
                return None
            raw_text = choices[0].get("message", {}).get("content", "").strip()
            if not raw_text:
                return None

            cleaned_text = re.sub(r"^```(?:json)?\s*", "", raw_text)
            cleaned_text = re.sub(r"\s*```$", "", cleaned_text).strip()

            data = json.loads(cleaned_text)
            if not isinstance(data, dict):
                return None

            summary = str(data.get("summary", "")).strip()
            detailed_summary = str(data.get("detailed_summary", "")).strip()
            key_takeaways = self._clean_list(data.get("key_takeaways"))

            if not summary and not detailed_summary and not key_takeaways:
                logger.warning("Groq returned empty or blank analysis fields")
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
                model_name=model_name,
                generated_at=int(time.time() * 1000),
            )
        except Exception as e:
            logger.warning("Error deserializing Groq JSON: %s", e)
            return None

    @staticmethod
    def _clean_list(val: Any) -> List[str]:
        if isinstance(val, list):
            return [str(item).strip() for item in val if str(item).strip()]
        if isinstance(val, str) and val.strip():
            return [line.strip() for line in val.split("\n") if line.strip()]
        return []
