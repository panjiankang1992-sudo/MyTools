#!/usr/bin/env python3
"""Record a task terminal scenario on its bound Media Library analysis."""
import json,os,tempfile,urllib.request
from pathlib import Path

SCENARIOS={"analysis_failure":("FAILED","MEDIA_ANALYSIS_FAILED"),"analysis_timeout":("TIMED_OUT","MEDIA_ANALYSIS_TIMEOUT"),"analysis_cancel":("CANCELLED","MEDIA_ANALYSIS_CANCELLED")}
def execute(context,base_url,token,requester=urllib.request.urlopen):
 """Idempotently record the terminal scenario when an analysis binding exists."""
 if not token:raise ValueError("Media Library internal token is missing")
 status,error=SCENARIOS.get(str(context.get("stepName")),("FAILED","MEDIA_ANALYSIS_FAILED"));media_id=str(context["parameters"]["mediaItemId"])
 payload={"taskInstanceId":str(context["taskInstanceId"]),"status":status,"errorCode":error}
 request=urllib.request.Request(base_url.rstrip("/")+f"/internal/v1/media/items/{media_id}/analyses/fail",data=json.dumps(payload,separators=(",",":")).encode(),method="POST",headers={"Authorization":f"Bearer {token}","Content-Type":"application/json"})
 with requester(request,timeout=30)as response:response.read()
 return {"mediaItemId":media_id,"status":status}
def write_result(result):
 """Atomically write the task result."""
 target=Path(os.environ["TASK_RESULT_FILE"]);target.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False)as handle:json.dump(result,handle,separators=(",",":"));temporary=Path(handle.name)
 temporary.replace(target)
def main():
 """Record the current scenario."""
 context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"));write_result(execute(context,os.getenv("MEDIA_LIBRARY_URL","http://127.0.0.1:23300"),os.environ.get("MEDIA_LIBRARY_INTERNAL_TOKEN","")))
if __name__=="__main__":main()
