#!/usr/bin/env python3
"""Bind one versioned Media Library analysis to the running task."""
import json,os,tempfile,urllib.request
from pathlib import Path

def execute(context,base_url,token,requester=urllib.request.urlopen):
 """Idempotently begin the analysis after validating its asset identity."""
 if not token:raise ValueError("Media Library internal token is missing")
 parameters=context["parameters"];media_id=str(parameters["mediaItemId"])
 payload={"analysisVersion":str(parameters["analysisVersion"]),"taskInstanceId":str(context["taskInstanceId"]),"assetId":str(parameters["assetRegistryId"])}
 request=urllib.request.Request(base_url.rstrip("/")+f"/internal/v1/media/items/{media_id}/analyses",data=json.dumps(payload,separators=(",",":")).encode(),method="POST",headers={"Authorization":f"Bearer {token}","Content-Type":"application/json","Accept":"application/json"})
 with requester(request,timeout=30)as response:result=json.loads(response.read().decode())
 if result.get("status")!="RUNNING" or str(result.get("mediaItemId"))!=media_id:raise RuntimeError("Media Library did not begin the requested analysis")
 return {"analysisId":str(result["id"]),"mediaItemId":media_id,"status":"RUNNING"}
def write_result(result):
 """Atomically write the task result."""
 target=Path(os.environ["TASK_RESULT_FILE"]);target.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False)as handle:json.dump(result,handle,separators=(",",":"));temporary=Path(handle.name)
 temporary.replace(target)
def main():
 """Begin the current task analysis."""
 context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"));write_result(execute(context,os.getenv("MEDIA_LIBRARY_URL","http://127.0.0.1:23300"),os.environ.get("MEDIA_LIBRARY_INTERNAL_TOKEN","")))
if __name__=="__main__":main()
