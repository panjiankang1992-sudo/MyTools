"""Tests for the media tag comparison script."""

import importlib.util
from pathlib import Path
import unittest

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_compare_tags", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MediaCompareTagsTest(unittest.TestCase):
    """Validate deterministic tag reconciliation."""

    def test_compares_generated_step_output_with_legacy_tags(self):
        """The comparison should deduplicate tags and calculate Jaccard similarity."""
        result = MODULE.compare({
            "parameters": {"legacyTags": ["photo", "nature", "nature"]},
            "stepOutputs": {"generate_tags": {"tags": [
                {"name": "nature"}, {"name": "travel"},
            ]}},
        })
        self.assertEqual(["nature"], result["matchedTags"])
        self.assertFalse(result["exactMatch"])
        self.assertAlmostEqual(1 / 3, result["jaccardSimilarity"])


if __name__ == "__main__":
    unittest.main()
