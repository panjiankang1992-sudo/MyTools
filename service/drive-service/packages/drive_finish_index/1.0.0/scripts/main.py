#!/usr/bin/env python3
"""Synchronize task failure, timeout, or cancellation to the Drive index cursor."""
import json
import os
from pathlib import Path
import tempfile
import urllib.request

STATUS_BY_STEP={"on_failure":"FAILED","on_timeout":"TIMED_OUT","on_cancel":"CANCELLED"}

def execute(context:dict,base_url:str,token:str)->dict:
    """Finish the active Drive index run with the special step status."""
    status=STATUS_BY_STEP.get(str(context.get("stepName")))
    if not status: raise ValueError("Drive finish step kind is invalid")
    account=str(context["parameters"]["accountId"]); run_id=str(context["taskInstanceId"])
    request=urllib.request.Request(base_url.rstrip("/")+f"/internal/v1/drive/accounts/{account}/index-runs/{run_id}/{status}",
        data=b"",method="POST",headers={"Authorization":f"Bearer {token}"})
    with urllib.request.urlopen(request,timeout=30) as response:
        if response.status!=204: raise RuntimeError("Drive finish response is invalid")
    return {"status":status}

def main()->None:
    """Run one Drive index terminal hook."""
    context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result=execute(context,os.getenv("DRIVE_SERVICE_URL","http://127.0.0.1:23280"),os.getenv("DRIVE_INTERNAL_TOKEN",""))
    target=Path(os.environ["TASK_RESULT_FILE"]); target.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False) as handle:
        json.dump(result,handle,separators=(",",":")); temporary=Path(handle.name)
    temporary.replace(target)

if __name__=="__main__": main()
