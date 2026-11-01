"""Unit tests for worker configuration and Gemini model validation."""

import os
import unittest
from unittest.mock import patch

from workers.processor.src.config import (
    ALLOWED_GEMINI_MODELS,
    DEFAULT_GEMINI_MODEL,
    WorkerConfig,
)


class TestConfig(unittest.TestCase):

    def test_default_model_is_gemini_3_6_flash(self):
        config = WorkerConfig()
        self.assertEqual("gemini-3.6-flash", config.gemini_model)
        self.assertEqual("gemini-3.6-flash", DEFAULT_GEMINI_MODEL)

    def test_allowlist_contains_only_permitted_models(self):
        self.assertEqual(("gemini-3.6-flash", "gemini-3.8-flash"), ALLOWED_GEMINI_MODELS)

    def test_permitted_model_selection(self):
        c1 = WorkerConfig(gemini_model="gemini-3.6-flash")
        self.assertEqual("gemini-3.6-flash", c1.gemini_model)

        c2 = WorkerConfig(gemini_model="gemini-3.8-flash")
        self.assertEqual("gemini-3.8-flash", c2.gemini_model)

    def test_disallowed_models_raise_value_error(self):
        disallowed = [
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-pro",
            "gpt-4",
            "",
            "unknown",
        ]
        for model in disallowed:
            with self.assertRaises(ValueError):
                WorkerConfig(gemini_model=model)

    @patch.dict(os.environ, {"GEMINI_MODEL": "gemini-3.8-flash"})
    def test_from_env_loads_allowed_override(self):
        config = WorkerConfig.from_env()
        self.assertEqual("gemini-3.8-flash", config.gemini_model)

    @patch.dict(os.environ, {"GEMINI_MODEL": "gemini-2.5-flash"})
    def test_from_env_rejects_disallowed_override(self):
        with self.assertRaises(ValueError):
            WorkerConfig.from_env()


if __name__ == "__main__":
    unittest.main()
