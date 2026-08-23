import importlib.util
import json
import os
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_source_discovery", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReaderSourceDiscoveryTest(unittest.TestCase):

    def test_discovers_and_saves_direct_json(self):
        payload = json.dumps([
            {"bookSourceUrl": "https://one.example", "bookSourceName": "One"},
            {"bookSourceUrl": "file:///invalid", "bookSourceName": "Invalid"},
        ]).encode()
        batches = []

        def fetcher(url, accept, maximum_bytes):
            return payload, "application/json"

        def saver(base_url, token, request_id, sources):
            batches.append(sources)
            return len(sources), 0

        previous = os.environ.get("READER_INTERNAL_TOKEN")
        os.environ["READER_INTERNAL_TOKEN"] = "test-token"
        try:
            result = MODULE.execute({
                "requestId": "00000000-0000-4000-8000-000000000001",
                "ownerId": 7,
                "url": "https://repository.example/sources.json",
            }, saver=saver, fetcher=fetcher)
        finally:
            if previous is None:
                os.environ.pop("READER_INTERNAL_TOKEN", None)
            else:
                os.environ["READER_INTERNAL_TOKEN"] = previous

        self.assertEqual(1, result["saved"])
        self.assertEqual(1, len(batches[0]))

    def test_rejects_unsupported_site(self):
        with self.assertRaises(ValueError):
            MODULE.discover("https://unsupported.example/page", lambda *args: (b"", ""))


if __name__ == "__main__":
    unittest.main()
