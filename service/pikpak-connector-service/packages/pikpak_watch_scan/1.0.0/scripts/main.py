#!/usr/bin/env python3
"""扫描全部 PikPak watcher，并为稳定批次创建下载请求。"""
from __future__ import annotations
import json, os, tempfile
from pathlib import Path
from urllib.request import Request, urlopen

def request(url: str, token: str, payload: dict) -> object:
    """调用一个受信内部 JSON 接口。"""
    value = json.dumps(payload, separators=(",", ":")).encode()
    with urlopen(Request(url, data=value, method="POST", headers={"Authorization": f"Bearer {token}",
            "Content-Type": "application/json"}), timeout=60) as response:
        return json.loads(response.read().decode())

def execute(connector_url: str, connector_token: str, download_url: str, download_token: str) -> dict:
    """扫描并幂等创建每个稳定批次的业务请求。"""
    scans = request(connector_url.rstrip("/") + "/api/internal/v1/pikpak/watchers/scan", connector_token, {})
    if not isinstance(scans, list) or len(scans) > 100:
        raise RuntimeError("PikPak watcher scan returned invalid accounts")
    request_ids = []
    ready = 0
    for scan in scans:
        for batch in scan.get("batches", []):
            if batch.get("phase") != "READY": continue
            ready += 1
            batch_id = str(batch["id"])
            result = request(download_url.rstrip("/") + "/api/v1/download-requests", download_token, {
                "idempotencyKey": f"pikpak-watch:{batch_id}", "sourceType": "PIKPAK_WATCHER",
                "sourceKey": batch_id, "requestKind": "PIKPAK_WATCH_BATCH", "ownerId": 0,
                "parameters": {"batchId": batch_id, "accountId": str(scan["accountId"])}})
            request_ids.append(str(result["id"]))
    return {"accountCount": len(scans), "readyBatchCount": ready, "downloadRequestIds": request_ids}

def main() -> None:
    """执行 watcher 扫描任务。"""
    result = execute(os.environ["PIKPAK_CONNECTOR_URL"], os.environ["PIKPAK_CONNECTOR_TOKEN"],
        os.environ["DOWNLOAD_INGESTION_URL"], os.environ["DOWNLOAD_INGESTION_TOKEN"])
    target=Path(os.environ["TASK_RESULT_FILE"]); target.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.NamedTemporaryFile("w",dir=target.parent,delete=False) as handle:
        json.dump(result,handle,separators=(",",":")); temporary=Path(handle.name)
    temporary.replace(target)
if __name__ == "__main__": main()
