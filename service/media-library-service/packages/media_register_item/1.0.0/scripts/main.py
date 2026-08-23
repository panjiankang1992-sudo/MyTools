#!/usr/bin/env python3
"""Register a probed legacy media file in Media Library by Asset Registry identity."""
import json,os,tempfile,urllib.request
from pathlib import Path
def execute(context,base_url,token,requester=urllib.request.urlopen):
 """Send only logical identity and media metadata to Media Library."""
 parameters=context["parameters"];registered=(context.get("stepOutputs")or{}).get("register_asset")or{};asset_id=str(registered.get("assetId")or"")
 if not asset_id:raise ValueError("registered media asset is missing")
 source=Path(str(parameters["sourcePath"]));probe=(context.get("stepOutputs")or{}).get("probe")or{}
 payload={"eventId":f"media-probe:{context['taskInstanceId']}","assetId":asset_id,"ownerId":int(parameters.get("ownerId")or 0),"sourceType":str(parameters.get("assetSourceType")or"MEDIA_FILE"),"sourceBusinessId":str(parameters.get("assetSourceBusinessId")or parameters["assetId"]),"displayName":source.name,"mimeType":str(parameters.get("assetMimeType")or"application/octet-stream"),"sizeBytes":source.stat().st_size,"contentSha256":str(parameters["contentSha256"]).lower(),"directoryKey":parameters.get("directoryKey"),"directoryName":parameters.get("directoryName"),"scanId":parameters.get("scanId")}
 request=urllib.request.Request(base_url.rstrip("/")+"/internal/v1/media/asset-events",data=json.dumps(payload,separators=(",",":")).encode(),method="POST",headers={"Authorization":f"Bearer {token}","Content-Type":"application/json","Accept":"application/json"})
 with requester(request,timeout=30) as response:result=json.loads(response.read().decode())
 return {"mediaItemId":str(result["id"]),"assetId":asset_id,"version":int(result["version"])}
def main():
 """Run one media item registration step."""
 context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"));result=execute(context,os.getenv("MEDIA_LIBRARY_URL","http://127.0.0.1:23300"),os.getenv("MEDIA_LIBRARY_INTERNAL_TOKEN",""));target=Path(os.environ["TASK_RESULT_FILE"]);target.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False)as handle:json.dump(result,handle,separators=(",",":"));temporary=Path(handle.name)
 temporary.replace(target)
if __name__=="__main__":main()
