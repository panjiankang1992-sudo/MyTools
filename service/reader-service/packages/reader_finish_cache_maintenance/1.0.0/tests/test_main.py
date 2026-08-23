import importlib.util
import json
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_finish_cache_maintenance", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return json.dumps({"status": "TIMED_OUT"}).encode()


class ReaderFinishCacheMaintenanceTest(unittest.TestCase):

    def test_maps_timeout_hook(self):
        captured = []

        def opener(request, timeout):
            captured.append(request.full_url)
            return Response()

        result = MODULE.execute({"stepName": "on_timeout", "parameters": {
            "maintenanceId": "00000000-0000-4000-8000-000000000001"}},
            "http://reader", "token", opener)

        self.assertEqual("TIMED_OUT", result["status"])
        self.assertIn("status=TIMED_OUT", captured[0])


if __name__ == "__main__":
    unittest.main()
