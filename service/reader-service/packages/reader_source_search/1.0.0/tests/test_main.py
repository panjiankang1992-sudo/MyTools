"""Tests for the reader source search task."""

import importlib.util
from pathlib import Path
import unittest

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_source_search", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeClient:
    """Return one successful source and one failed source."""

    def __init__(self):
        self.replaced = []

    def replace_sources(self, sources):
        """Capture source synchronization."""
        self.replaced = sources

    def search(self, source_url, keyword, page):
        """Return deterministic source rows."""
        if source_url.endswith("bad"):
            raise RuntimeError("unavailable")
        return [{"name": "Example Book", "author": "Author", "bookUrl": "/book/1"}]


class ReaderSourceSearchTest(unittest.TestCase):
    """Validate partial success and result normalization."""

    def test_preserves_results_when_one_source_fails(self):
        """A failed source must not discard another source's results."""
        sources = [
            {"id": "one", "url": "https://source/good", "name": "Good", "snapshot": {"enabled": True}},
            {"id": "two", "url": "https://source/bad", "name": "Bad", "snapshot": {"enabled": True}},
        ]
        client = FakeClient()
        result = MODULE.execute(
            {"userId": 7, "keyword": "Example", "page": 1, "mode": "FUZZY", "sources": sources}, client)
        self.assertEqual(1, result["successfulSources"])
        self.assertEqual(1, result["failedSources"])
        self.assertEqual("Example Book", result["results"][0]["name"])
        self.assertEqual(2, len(client.replaced))


if __name__ == "__main__":
    unittest.main()
