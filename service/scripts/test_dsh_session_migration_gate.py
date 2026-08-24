import importlib.util
from pathlib import Path
SCRIPT=Path(__file__).with_name("dsh_session_migration_gate.py");SPEC=importlib.util.spec_from_file_location("dsh_gate",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
def report(dry):return {"dryRun":dry,"sourceHighWater":2,"exported":2,"accepted":2,"skipped":0,"rejected":0,"digestSha256":"a"*64}
def replay():return {**report(False),"accepted":0,"skipped":2}
def target():return {"itemCount":2,"collectionSha256":"b"*64}
def test_accepts_complete_dsh_migration():assert MODULE.evaluate(report(True),report(False),replay(),target())["ready"]
def test_rejects_non_idempotent_replay():
 result=MODULE.evaluate(report(True),report(False),report(False),target());assert not result["ready"] and "REPLAY_NOT_IDEMPOTENT"in result["errors"]
