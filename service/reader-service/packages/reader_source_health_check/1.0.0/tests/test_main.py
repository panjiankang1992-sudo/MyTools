import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("reader_source_health_check", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Client:
    def __init__(self):
        self.snapshots = []

    def replace_sources(self, snapshots):
        self.snapshots = snapshots

    def check(self, source_url, keyword):
        if source_url.endswith("bad"):
            raise TimeoutError("timeout")


class ReaderSourceHealthCheckTest(unittest.TestCase):

    def test_checks_only_assigned_shard_and_retains_failures(self):
        sources = [{"id": str(index), "url": f"https://source/{'bad' if index == 2 else index}",
                    "snapshot": {"bookSourceUrl": f"https://source/{index}"}} for index in range(5)]
        client = Client()

        result = MODULE.execute({"requestId": "request", "keyword": "test", "sources": sources,
                                 "taskExecutionTarget": {"index": 0, "count": 2}}, client)

        self.assertEqual(3, result["checked"])
        self.assertEqual(2, result["healthy"])
        self.assertEqual(1, result["unhealthy"])
        self.assertEqual(3, len(client.snapshots))


if __name__ == "__main__":
    unittest.main()
