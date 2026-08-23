#!/usr/bin/env python3
"""Publish storyboard frames and register immutable derived assets."""
import json,os,tempfile
from pathlib import Path
from mytools_task_sdk.asset import AssetRegistryClient
from mytools_task_sdk.storage import StorageGatewayClient

def execute(context,storage,assets):
 """Publish and link every verified storyboard frame in index order."""
 parameters=context["parameters"];frames=((context.get("stepOutputs")or{}).get("generate_storyboard")or{}).get("frames")or[]
 if not frames or len(frames)>12:raise ValueError("generated storyboard is missing or too large")
 parent_id=str(parameters["assetRegistryId"]);version=int(((context.get("stepOutputs")or{}).get("register_thumbnail")or{}).get("parentVersion")or parameters.get("assetRegistryVersion")or 0)
 if version<1:raise ValueError("parent asset version is missing")
 results=[]
 for frame in sorted(frames,key=lambda item:int(item["index"])):
  index=int(frame["index"]);path=Path(str(frame["artifactPath"]));sha=str(frame["artifactSha256"]).lower();size=int(frame["size"])
  if not path.is_file() or path.stat().st_size!=size:raise ValueError("storyboard frame does not match declared size")
  relative=f"media/storyboards/{parent_id}/{parameters['analysisVersion']}/{index:02d}-{sha}.jpg";key=f"media-storyboard:{parent_id}:{parameters['analysisVersion']}:{index}:{sha}"
  uri=storage.publish(path,str(parameters.get("storageRoot")or"managed"),relative,key,size,sha)
  artifact=assets.register({"ownerId":int(parameters["ownerId"]),"idempotencyKey":key,"sourceType":"MEDIA_STORYBOARD_FRAME","sourceBusinessId":f"{parent_id}:{parameters['analysisVersion']}:{index}:{sha}","contentSha256":sha,"sizeBytes":size,"mimeType":"image/jpeg","location":{"idempotencyKey":key+":location","providerType":"STORAGE_GATEWAY","storageUri":uri,"providerVersion":"v1"}})
  linked=assets.register_artifact(parent_id,{"expectedAssetVersion":version,"artifactAssetId":str(artifact["id"]),"idempotencyKey":key+":artifact","artifactKind":f"STORYBOARD_FRAME_{index:02d}","generatorName":"media_generate_storyboard","generatorVersion":str(parameters["analysisVersion"])})
  version=int(linked["version"]);results.append({"index":index,"assetId":str(artifact["id"]),"storageUri":uri})
 return {"parentAssetId":parent_id,"parentVersion":version,"artifacts":results}
def write_result(result):
 """Atomically write the task result."""
 target=Path(os.environ["TASK_RESULT_FILE"]);target.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False)as handle:json.dump(result,handle,separators=(",",":"));temporary=Path(handle.name)
 temporary.replace(target)
def main():
 """Register the current storyboard."""
 context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"));storage=StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL","http://127.0.0.1:23240"),os.environ.get("STORAGE_INTERNAL_TOKEN",""));assets=AssetRegistryClient(os.getenv("ASSET_REGISTRY_URL","http://127.0.0.1:23270"),os.environ.get("ASSET_REGISTRY_INTERNAL_TOKEN",""));write_result(execute(context,storage,assets))
if __name__=="__main__":main()
