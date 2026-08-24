#!/usr/bin/env python3
"""Validate DSH session binding migration evidence."""
from __future__ import annotations
import argparse,json,re,sys
from pathlib import Path
DIGEST=re.compile(r"^[a-f0-9]{64}$")
def read(path:Path)->dict:
 """Read one JSON report."""
 value=json.loads(path.read_text());
 if not isinstance(value,dict):raise ValueError("invalid report")
 return value
def evaluate(dry:dict,apply:dict,replay:dict,target:dict)->dict:
 """Validate stable source, complete import and replay."""
 errors=[]
 if dry.get("dryRun")is not True or apply.get("dryRun")is not False or replay.get("dryRun")is not False:errors.append("MODE_INVALID")
 if len({dry.get("migrationKey"),apply.get("migrationKey"),replay.get("migrationKey"),target.get("migrationKey")})!=1:errors.append("MIGRATION_KEY_MISMATCH")
 if len({dry.get("sourceHighWater"),apply.get("sourceHighWater"),replay.get("sourceHighWater")})!=1 or len({dry.get("digestSha256"),apply.get("digestSha256"),replay.get("digestSha256")})!=1 or DIGEST.fullmatch(str(dry.get("digestSha256")or""))is None:errors.append("SOURCE_CHANGED")
 count=dry.get("exported")
 if not isinstance(count,int)or any(value.get("exported")!=count for value in(apply,replay)):errors.append("COUNT_MISMATCH")
 if any(value.get("rejected")!=0 for value in(dry,apply,replay))or apply.get("accepted",0)+apply.get("skipped",0)!=count:errors.append("IMPORT_INCOMPLETE")
 if replay.get("accepted")!=0 or replay.get("skipped")!=count:errors.append("REPLAY_NOT_IDEMPOTENT")
 if target.get("itemCount")!=count or target.get("collectionSha256")!=apply.get("digestSha256")or DIGEST.fullmatch(str(target.get("collectionSha256")or""))is None:errors.append("TARGET_MISMATCH")
 if dry.get("targetVerified")is not False or apply.get("targetVerified")is not True or replay.get("targetVerified")is not True:errors.append("TARGET_NOT_VERIFIED")
 return {"ready":not errors,"itemCount":count if isinstance(count,int)else-1,"errors":sorted(set(errors))}
def main()->None:
 """Run the offline evidence gate."""
 parser=argparse.ArgumentParser();parser.add_argument("--dry-run-report",type=Path,required=True);parser.add_argument("--apply-report",type=Path,required=True);parser.add_argument("--replay-report",type=Path,required=True);parser.add_argument("--target-report",type=Path,required=True);args=parser.parse_args()
 try:result=evaluate(read(args.dry_run_report),read(args.apply_report),read(args.replay_report),read(args.target_report))
 except (OSError,ValueError,json.JSONDecodeError):result={"ready":False,"itemCount":-1,"errors":["REPORT_INVALID"]}
 print(json.dumps(result,separators=(",",":")))
 if not result["ready"]:sys.exit(2)
if __name__=="__main__":main()
