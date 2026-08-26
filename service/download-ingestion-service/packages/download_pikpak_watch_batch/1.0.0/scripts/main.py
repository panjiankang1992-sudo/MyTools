#!/usr/bin/env python3
"""物化一个稳定 PikPak watcher 批次并在成功后归档远端来源。"""
from __future__ import annotations
import hashlib, json, os, tempfile, time
from pathlib import Path
from urllib.request import Request, urlopen
from uuid import UUID
from mytools_task_sdk.context import TaskContext
from mytools_task_sdk.orchestration import wait_all_or_cancel

def call(base: str, token: str, method: str, path: str) -> dict:
    """调用 Connector 批次接口。"""
    data = b"{}" if method == "POST" else None
    with urlopen(Request(base.rstrip("/") + path, data=data, method=method,
            headers={"Authorization": f"Bearer {token}", "Content-Type":"application/json"}), timeout=60) as response:
        value=json.loads(response.read().decode())
    if not isinstance(value,dict): raise RuntimeError("PikPak Connector returned invalid batch")
    return value

def execute(context: TaskContext, base: str, token: str, sleeper=time.sleep) -> dict:
    """创建逐文件任务，全部成功后推进持久化归档任务。"""
    request_id=str(UUID(str(context.parameters["downloadRequestId"])))
    batch_id=str(UUID(str(context.parameters["batchId"])))
    batch=call(base,token,"GET",f"/api/internal/v1/pikpak/watch-batches/{batch_id}")
    if batch.get("phase") not in {"READY","MOVING","ARCHIVED"}: raise RuntimeError("PikPak watch batch is not ready")
    children=[]
    for index,item in enumerate(batch.get("items",[]),start=1):
        item_id=f"pikpak-watch:{batch_id}:{index}"; relative=str(item["relativePath"])
        fingerprint=hashlib.sha256(str(item["remoteFileId"]).encode()).hexdigest()
        children.append(context.create_child("download_remote_storage_object",{
            "downloadRequestId":request_id,"itemId":item_id,"sourceProviderId":str(item["storageProviderId"]),
            "sourcePath":str(item["storagePath"]),"fileName":Path(relative).name,
            "expectedSize":int(item["sizeBytes"]),"maxBytes":20*1024*1024*1024,
            "destinationRelativePath":relative,
            "destinationRootName":os.getenv("DOWNLOAD_STORAGE_ROOT", "downloads"),"ownerId":0,
            "assetSourceBusinessId":f"{request_id}:{item_id}"},
            f"pikpak-watch-object:{batch_id}:{fingerprint}",business_type="DOWNLOAD_REQUEST",business_id=request_id))
    if not children: raise RuntimeError("PikPak watch batch has no files")
    wait_all_or_cancel(context,children,21600)
    for _ in range(720):
        batch=call(base,token,"POST",f"/api/internal/v1/pikpak/watch-batches/{batch_id}/archive")
        if batch.get("phase") in {"ARCHIVED","FAILED"}: break
        sleeper(max(1,min(int(batch.get("retryAfterSeconds",10)),60)))
    if batch.get("phase") != "ARCHIVED": raise RuntimeError("PikPak watch batch archive failed")
    return {"requestId":request_id,"batchId":batch_id,"status":"ARCHIVED","childTaskIds":[c.id for c in children]}

def main() -> None:
    """执行 watcher 批次父任务。"""
    context=TaskContext.load(); result=execute(context,os.environ["PIKPAK_CONNECTOR_URL"],os.environ["PIKPAK_CONNECTOR_TOKEN"])
    target=Path(os.environ["TASK_RESULT_FILE"]); target.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.NamedTemporaryFile("w",dir=target.parent,delete=False) as handle:
        json.dump(result,handle,separators=(",",":")); temporary=Path(handle.name)
    temporary.replace(target)
if __name__ == "__main__": main()
