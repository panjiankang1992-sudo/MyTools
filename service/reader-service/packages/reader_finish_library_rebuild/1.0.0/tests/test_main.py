import importlib.util
import json
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_finish_library_rebuild", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)

class Response:
    def __enter__(self): return self
    def __exit__(self, *_): return False
    def read(self): return json.dumps({"status":"FAILED"}).encode()

class ReaderFinishLibraryRebuildTest(unittest.TestCase):
    def test_maps_failure_hook(self):
        result = MODULE.execute({"stepName":"on_failure","parameters":{"rebuildId":"id"}},
                                "http://reader", "token", lambda request, timeout: Response())
        self.assertEqual("FAILED", result["status"])

if __name__ == "__main__": unittest.main()
