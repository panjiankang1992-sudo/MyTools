import importlib.util
from pathlib import Path
import sys
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
spec = importlib.util.spec_from_file_location("reader_probe_search_main", SCRIPT)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class Child:
    id = "10000000-0000-4000-8000-000000000001"
    status = "SUCCEEDED"


class Context:
    context = {"taskInstanceId": "20000000-0000-4000-8000-000000000001"}
    parameters = {"userId": 7, "keyword": "lost prince", "page": 1, "mode": "PROBE",
                  "sources": [{"id": 1}]}

    def __init__(self):
        self.created = None

    def create_child(self, name, parameters, key, **metadata):
        self.created = name, parameters, key, metadata
        return Child()

    def wait_child(self, task_id, timeout, poll):
        return Child()

    def get_task_results(self, task_id):
        return {"status": "SUCCEEDED", "steps": [
            {"stepName": "search_sources", "status": "SUCCEEDED", "result": {
                "totalSources": 2, "successfulSources": 1, "failedSources": 0,
                "results": [{"name": "Book A"}]}},
            {"stepName": "search_sources", "status": "SUCCEEDED", "result": {
                "totalSources": 2, "successfulSources": 1, "failedSources": 0,
                "results": [{"name": " book a "}, {"name": "Book B"}]}}
        ]}


class Client:
    def analyze(self, owner_id, task_instance_id, clue):
        return ["term one", "term two"]


class ProbeSearchTest(unittest.TestCase):
    def test_creates_child_with_frozen_terms_and_aggregates_results(self):
        context = Context()
        result = module.execute(context, Client())
        self.assertEqual("reader_source_search", context.created[0])
        self.assertEqual(["term one", "term two"], context.created[1]["searchTerms"])
        self.assertEqual(2, result["successfulSources"])
        self.assertEqual(["Book A", "Book B"], [row["name"] for row in result["results"]])

    def test_rejects_failed_child_result(self):
        with self.assertRaises(RuntimeError):
            module.aggregate({"status": "FAILED", "steps": []})


if __name__ == "__main__":
    unittest.main()
