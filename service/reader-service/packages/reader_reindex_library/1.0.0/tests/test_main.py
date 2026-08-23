import importlib.util
import json
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_reindex_library", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __init__(self, value): self.value = value
    def __enter__(self): return self
    def __exit__(self, *_): return False
    def read(self): return json.dumps(self.value).encode()


class ReaderReindexLibraryTest(unittest.TestCase):

    def test_publishes_only_after_done_batch(self):
        values = iter([{"indexed": 2, "indexedTotal": 2, "done": False},
                       {"indexed": 1, "indexedTotal": 3, "done": True},
                       {"status": "SUCCEEDED"}])
        urls = []
        def opener(request, timeout):
            urls.append(request.full_url)
            return Response(next(values))

        result = MODULE.execute("00000000-0000-4000-8000-000000000001", "http://reader", "token", opener)

        self.assertEqual(3, result["indexedTotal"])
        self.assertTrue(urls[-1].endswith("/publish"))


if __name__ == "__main__": unittest.main()
