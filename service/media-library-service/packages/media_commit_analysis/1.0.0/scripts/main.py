#!/usr/bin/env python3
"""Commit normalized media intelligence results to Media Library."""
import json,os,tempfile,urllib.request
from pathlib import Path

def build_payload(context):
 """Build a bounded domain payload from preceding verified step outputs."""
 outputs=context.get("stepOutputs")or{};description=outputs.get("describe_video")or{};tag_output=outputs.get("generate_tags")or{}
 tags=[{"name":str(item["name"]),"confidence":float(item.get("confidence",0))}for item in tag_output.get("tags",[])][:32]
 artifacts=[];thumbnail=outputs.get("register_thumbnail")or{}
 version=str(context["parameters"]["analysisVersion"])
 if thumbnail.get("artifactAssetId"):artifacts.append({"assetId":str(thumbnail["artifactAssetId"]),"kind":"THUMBNAIL","generatorVersion":version})
 for item in (outputs.get("register_storyboard")or{}).get("artifacts",[])[:12]:artifacts.append({"assetId":str(item["assetId"]),"kind":f"STORYBOARD_FRAME_{int(item['index']):02d}","generatorVersion":version})
 return {"taskInstanceId":str(context["taskInstanceId"]),"summary":description.get("summary"),"description":description.get("description"),"tags":tags,"artifacts":artifacts}
def execute(context,base_url,token,requester=urllib.request.urlopen):
 """Commit the analysis through its task binding."""
 if not token:raise ValueError("Media Library internal token is missing")
 media_id=str(context["parameters"]["mediaItemId"]);payload=build_payload(context)
 request=urllib.request.Request(base_url.rstrip("/")+f"/internal/v1/media/items/{media_id}/analyses/complete",data=json.dumps(payload,separators=(",",":")).encode(),method="POST",headers={"Authorization":f"Bearer {token}","Content-Type":"application/json"})
 with requester(request,timeout=30)as response:response.read()
 return {"mediaItemId":media_id,"status":"SUCCEEDED","tagCount":len(payload["tags"]),"artifactCount":len(payload["artifacts"])}
def write_result(result):
 """Atomically write the task result."""
 target=Path(os.environ["TASK_RESULT_FILE"]);target.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False)as handle:json.dump(result,handle,separators=(",",":"));temporary=Path(handle.name)
 temporary.replace(target)
def main():
 """Commit the current task analysis."""
 context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"));write_result(execute(context,os.getenv("MEDIA_LIBRARY_URL","http://127.0.0.1:23300"),os.environ.get("MEDIA_LIBRARY_INTERNAL_TOKEN","")))
if __name__=="__main__":main()
