import importlib.util,os
from pathlib import Path
SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py";SPEC=importlib.util.spec_from_file_location("acceptance",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
def test_success_and_terminal_results(tmp_path):
 target=tmp_path/"result.json";os.environ["TASK_RESULT_FILE"]=str(target)
 assert MODULE.execute({"parameters":{"scenario":"success"}},None)==0 and '"accepted":true'in target.read_text()
 assert MODULE.execute({"parameters":{"scenario":"cancel"}},"cancel")==0 and '"terminal":"cancel"'in target.read_text()
def test_failure_has_nonzero_exit(tmp_path):
 os.environ["TASK_RESULT_FILE"]=str(tmp_path/"result.json")
 assert MODULE.execute({"parameters":{"scenario":"failure"}},None)==7
