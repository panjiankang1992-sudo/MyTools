"""Tests for Drive index terminal hooks."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py"
SPEC=importlib.util.spec_from_file_location("drive_finish_index",SCRIPT)
MODULE=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)

class Response:
    status=204
    def __enter__(self): return self
    def __exit__(self,*_args): return False

class DriveFinishIndexTest(unittest.TestCase):
    @patch.object(MODULE.urllib.request,"urlopen",return_value=Response())
    def test_maps_timeout_step_to_terminal_status(self,urlopen):
        result=MODULE.execute({"stepName":"on_timeout","taskInstanceId":"run-1",
            "parameters":{"accountId":"account-1"}},"http://drive","token")
        self.assertEqual("TIMED_OUT",result["status"])
        self.assertIn("/TIMED_OUT",urlopen.call_args.args[0].full_url)

if __name__=="__main__": unittest.main()
