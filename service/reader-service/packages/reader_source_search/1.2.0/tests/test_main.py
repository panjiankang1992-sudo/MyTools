"""Tests for sharded reader source search."""

import importlib.util
from pathlib import Path
import unittest

SCRIPT_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_source_search_sharded", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeClient:
    """Capture the assigned source snapshots and return deterministic rows."""

    def __init__(self):
        self.replaced = []

    def replace_sources(self, sources):
        """Capture source synchronization."""
        self.replaced = sources

    def search(self, source_url, _keyword, _page):
        """Return one row containing its source URL."""
        return [{"name": "Example Book", "author": "Author", "bookUrl": source_url + "/book"}]


class ReaderSourceSearchShardTest(unittest.TestCase):
    """Validate deterministic source partitioning."""

    def test_searches_only_sources_assigned_to_target(self):
        """Modulo partitioning must assign every source to exactly one target."""
        sources = [{"id": str(index), "url": f"https://source/{index}", "snapshot": {"index": index}}
                   for index in range(5)]
        first_client = FakeClient()
        second_client = FakeClient()
        common = {"userId": 7, "keyword": "Example", "searchTerms": ["Example"],
                  "page": 1, "mode": "FUZZY", "sources": sources}
        first = MODULE.execute({**common, "taskExecutionTarget": {"index": 0, "count": 2}}, first_client)
        second = MODULE.execute({**common, "taskExecutionTarget": {"index": 1, "count": 2}}, second_client)

        self.assertEqual(3, first["assignedSources"])
        self.assertEqual(2, second["assignedSources"])
        self.assertEqual({0, 2, 4}, {item["index"] for item in first_client.replaced})
        self.assertEqual({1, 3}, {item["index"] for item in second_client.replaced})

    def test_probe_searches_every_expanded_term_and_deduplicates(self):
        """Probe mode must search bounded expanded terms without changing the original keyword."""
        client = FakeClient()
        result = MODULE.execute({"userId": 7, "keyword": "plot clue", "page": 1, "mode": "PROBE",
                                 "searchTerms": ["Example", "Example Book"],
                                 "sources": [{"id": "1", "url": "https://source/1", "snapshot": {}}]},
                                client)

        self.assertEqual("PROBE", result["mode"])
        self.assertEqual("plot clue", result["keyword"])
        self.assertEqual(1, len(result["results"]))


if __name__ == "__main__":
    unittest.main()
