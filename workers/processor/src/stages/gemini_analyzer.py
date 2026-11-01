"""Gemini AI structured knowledge extraction for Reelix."""

from __future__ import annotations

import json
import logging
import re
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional
import urllib.request
import urllib.error

from ..models import GeminiAnalysisResult

logger = logging.getLogger(__name__)

GEMINI_API_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

SYSTEM_INSTRUCTION = """You are Reelix, an intelligent knowledge extraction system for personal saved content.
Analyze the provided transcript, caption, and metadata. Generate structured, actionable knowledge.

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
- If no transcript or detailed text is provided, summarize available title/metadata honestly.
- Output MUST be valid JSON only without markdown wrapping or preamble.
"""


@dataclass
class GeminiAnalysisOutcome:
    success: bool
    result: Optional[GeminiAnalysisResult] = None
    error: Optional[str] = None


class GeminiAnalyzer:
    """Interacts with Google Gemini free-tier API to extract structured knowledge."""

    def __init__(self, api_key: Optional[str], model: str = "gemini-1.5-flash"):
        self.api_key = api_key
        self.model = model

    def analyze(
        self,
        title: Optional[str] = None,
        caption: Optional[str] = None,
        transcript: Optional[str] = None,
        notes: Optional[str] = None,
    ) -> GeminiAnalysisOutcome:
        if not self.api_key:
            return GeminiAnalysisOutcome(
                success=False,
                error="GEMINI_API_KEY is not configured",
            )

        content_parts: List[str] = []
        if title:
            content_parts.append(f"Title: {title}")
        if caption:
            content_parts.append(f"Caption: {caption}")
        if notes:
            content_parts.append(f"Personal notes: {notes}")
        if transcript:
            content_parts.append(f"Audio Transcript:\n{transcript}")

        combined_text = "\n\n".join(content_parts).strip()
        if not combined_text:
            return GeminiAnalysisOutcome(
                success=False,
                error="No text (transcript, caption, or title) available for analysis",
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

        try:
            req_data = json.dumps(payload).encode("utf-8")
            req = urllib.request.Request(
                url,
                data=req_data,
                headers={"Content-Type": "application/json"},
                method="POST",
            )

            start_t = time.time()
            logger.info("Calling Gemini API (%s)...", self.model)
            with urllib.request.urlopen(req, timeout=60) as resp:
                resp_bytes = resp.read()
                elapsed = time.time() - start_t
                logger.info("Gemini API responded in %.2fs", elapsed)

            resp_json = json.loads(resp_bytes.decode("utf-8"))
            parsed_result = self._parse_gemini_response(resp_json)
            if parsed_result is None:
                return GeminiAnalysisOutcome(
                    success=False,
                    error="Failed to parse structured JSON from Gemini response",
                )

            return GeminiAnalysisOutcome(success=True, result=parsed_result)

        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")
            logger.error("Gemini API HTTP %d error: %s", e.code, err_body)
            return GeminiAnalysisOutcome(success=False, error=f"Gemini HTTP {e.code}: {err_body}")
        except Exception as e:
            logger.error("Unexpected error calling Gemini API: %s", e)
            return GeminiAnalysisOutcome(success=False, error=f"Gemini API error: {e}")

    def _parse_gemini_response(self, resp_json: Dict[str, Any]) -> Optional[GeminiAnalysisResult]:
        try:
            candidates = resp_json.get("candidates", [])
            if not candidates:
                return None
            parts = candidates[0].get("content", {}).get("parts", [])
            if not parts:
                return None
            raw_text = parts[0].get("text", "").strip()

            # Clean markdown code blocks if present
            cleaned_text = re.sub(r"^```(?:json)?\s*", "", raw_text)
            cleaned_text = re.sub(r"\s*```$", "", cleaned_text).strip()

            data = json.loads(cleaned_text)

            return GeminiAnalysisResult(
                summary=data.get("summary", ""),
                detailed_summary=data.get("detailed_summary", ""),
                main_topic=data.get("main_topic", ""),
                category=data.get("category", "General"),
                keywords=self._clean_list(data.get("keywords")),
                tags=self._clean_list(data.get("tags")),
                key_takeaways=self._clean_list(data.get("key_takeaways")),
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

