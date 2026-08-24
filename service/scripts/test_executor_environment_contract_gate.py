import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("executor_environment_contract_gate.py")
SPEC = importlib.util.spec_from_file_location("executor_environment_contract_gate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ExecutorEnvironmentContractGateTest(unittest.TestCase):

    def test_extracts_literal_environment_references(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "main.py"
            script.write_text("import os\nA=os.getenv('API_URL')\nB=os.environ['API_TOKEN']\n"
                              "C=os.environ.get('TASK_CONTEXT_FILE')\n", encoding="utf-8")
            self.assertEqual({"API_URL", "API_TOKEN", "TASK_CONTEXT_FILE"},
                             MODULE.environment_references(script))

    def test_reports_only_unconfigured_node_variables(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "service"
            script = root / "domain/packages/example/1.0.0/scripts/main.py"
            script.parent.mkdir(parents=True)
            script.write_text("import os\nA=os.getenv('API_URL')\nB=os.environ['API_TOKEN']\n"
                              "C=os.environ['TASK_RESULT_FILE']\n", encoding="utf-8")
            application = root / "application.yml"
            application.write_text("executor:\n  script-environments:\n    example:\n"
                                   "      API_URL: ${API_URL:}\n", encoding="utf-8")
            report = MODULE.evaluate(root, application)
            self.assertFalse(report["ready"])
            self.assertEqual({"example": ["API_TOKEN"]}, report["missing"])

    def test_accepts_complete_package_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "service"
            script = root / "domain/packages/example/1.0.0/scripts/main.py"
            script.parent.mkdir(parents=True)
            script.write_text("import os\nA=os.getenv('API_URL')\n", encoding="utf-8")
            application = root / "application.yml"
            application.write_text("executor:\n  script-environments:\n    example:\n"
                                   "      API_URL: ${API_URL:}\nserver:\n", encoding="utf-8")
            report = MODULE.evaluate(root, application)
            self.assertTrue(report["ready"])


if __name__ == "__main__":
    unittest.main()
