import importlib.util
from pathlib import Path
SCRIPT=Path(__file__).with_name("app_catalog_migration_gate.py");SPEC=importlib.util.spec_from_file_location("gate",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
def report(dry):return {"dryRun":dry,"sourceHighWater":"9","apps":2,"versions":3,"files":4,"accepted":2,"skipped":0,"rejected":0,"digestSha256":"a"*64}
def replay():return {**report(False),"accepted":0,"skipped":2}
def target():return {"appCount":2,"versionCount":3,"fileCount":4,"unresolvedFileCount":4,"digestSha256":"b"*64}
def test_accepts_matching_complete_evidence():assert MODULE.evaluate(report(True),report(False),replay(),target())["ready"]
def test_rejects_source_change_and_missing_target():
 applied=report(False);applied["digestSha256"]="c"*64;current=target();current["fileCount"]=3;result=MODULE.evaluate(report(True),applied,replay(),current);assert not result["ready"] and "SOURCE_CHANGED"in result["errors"] and "TARGET_COUNT_MISMATCH"in result["errors"]
