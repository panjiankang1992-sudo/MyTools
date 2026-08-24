import importlib.util
from pathlib import Path
SCRIPT=Path(__file__).with_name("feedback_migration_gate.py");SPEC=importlib.util.spec_from_file_location("feedback_gate",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
def report(dry):return {"migrationKey":"feedback-v1","dryRun":dry,"sourceHighWater":2,"exported":2,"accepted":2,"skipped":0,"rejected":0,"digestSha256":"a"*64,"targetVerified":not dry}
def replay():return {**report(False),"accepted":0,"skipped":2}
def target():return {"migrationKey":"feedback-v1","itemCount":2,"collectionSha256":"a"*64}
def test_accepts_complete_feedback_evidence():assert MODULE.evaluate(report(True),report(False),replay(),target())["ready"]
def test_rejects_changed_source_and_partial_target():
 applied=report(False);applied["digestSha256"]="c"*64;current=target();current["itemCount"]=1;result=MODULE.evaluate(report(True),applied,replay(),current);assert not result["ready"] and "SOURCE_CHANGED"in result["errors"] and "TARGET_MISMATCH"in result["errors"]
