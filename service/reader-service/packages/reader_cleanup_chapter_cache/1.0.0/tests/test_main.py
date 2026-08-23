import importlib.util
import json
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_cleanup_chapter_cache", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __init__(self, value):
        self.value = value

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return json.dumps(self.value).encode()


class ReaderCleanupChapterCacheTest(unittest.TestCase):

    def test_repeats_batches_and_marks_success(self):
        values = iter([{"deleted": 2, "deletedTotal": 2, "status": "RUNNING"},
                       {"deleted": 0, "deletedTotal": 2, "status": "RUNNING"},
                       {"status": "SUCCEEDED"}])
        urls = []

        def opener(request, timeout):
            urls.append(request.full_url)
            self.assertNotIn("token", request.full_url)
            return Response(next(values))

        result = MODULE.execute("00000000-0000-4000-8000-000000000001",
                                "http://reader", "token", opener)

        self.assertEqual(2, result["deletedTotal"])
        self.assertTrue(urls[-1].endswith("/finish?status=SUCCEEDED"))


if __name__ == "__main__":
    unittest.main()
