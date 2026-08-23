#!/usr/bin/env python3
"""通过持久化 Connector 状态机编排一个 PikPak magnet 操作。"""
from __future__ import annotations
import json
import hashlib
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen
from uuid import UUID
from mytools_task_sdk.context import TaskContext
from mytools_task_sdk.orchestration import wait_all_or_cancel

BTIH = re.compile(r"^(?:[0-9a-fA-F]{40}|[A-Z2-7]{32})$")
TERMINAL = {"READY", "FAILED", "CANCELLED"}

def validate_magnet(value: object) -> str:
    """只接受包含合法 BTIH 的 magnet URI。"""
    uri = str(value or "").strip()
    parsed = urlparse(uri)
    hashes = [item.rsplit(":", 1)[-1] for item in parse_qs(parsed.query).get("xt", []) if item.lower().startswith("urn:btih:")]
    if parsed.scheme.lower() != "magnet" or not hashes or not BTIH.fullmatch(hashes[0]):
        raise ValueError("magnetUri must contain one valid BTIH")
    if len(uri.encode("utf-8")) > 8192:
        raise ValueError("magnetUri exceeds maximum length")
    return uri

class ConnectorClient:
    """PikPak Connector 内部 HTTP 客户端。"""
    def __init__(self, base_url: str, token: str, timeout: float = 30):
        self.base_url, self.token, self.timeout = base_url.rstrip("/"), token, timeout
    def create(self, payload: dict) -> dict:
        """幂等创建离线操作。"""
        return self._request("POST", "/api/internal/v1/pikpak/operations", payload)
    def advance(self, operation_id: str, magnet_uri: str) -> dict:
        """推进一次持久化状态机。"""
        return self._request("POST", f"/api/internal/v1/pikpak/operations/{operation_id}/advance",
                             {"magnetUri": magnet_uri})
    def cancel(self, operation_id: str) -> dict:
        """请求取消离线操作。"""
        return self._request("POST", f"/api/internal/v1/pikpak/operations/{operation_id}/cancel", {})
    def _request(self, method: str, path: str, payload: dict) -> dict:
        request = Request(self.base_url + path, data=json.dumps(payload, separators=(",", ":")).encode(), method=method,
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json", "Accept": "application/json"})
        with urlopen(request, timeout=self.timeout) as response:
            result = json.loads(response.read().decode())
        if not isinstance(result, dict):
            raise RuntimeError("PikPak Connector returned an invalid response")
        return result

def execute(context: TaskContext, client: ConnectorClient, sleeper=time.sleep) -> dict:
    """创建并推进操作，Connector 负责全部可恢复检查点。"""
    parameters = context.parameters
    request_id = str(UUID(str(parameters["downloadRequestId"])))
    account_id = str(UUID(str(parameters["accountId"])))
    magnet_uri = validate_magnet(parameters["magnetUri"])
    operation = client.create({"accountId": account_id, "magnetUri": magnet_uri,
        "idempotencyKey": f"download:{request_id}:pikpak", "businessType": "DOWNLOAD_REQUEST", "businessId": request_id})
    operation_id = str(UUID(str(operation["id"])))
    maximum = int(parameters.get("maximumAdvances", 2160))
    if maximum < 1 or maximum > 4320:
        raise ValueError("maximumAdvances is outside the supported range")
    for _ in range(maximum):
        if str(operation.get("phase", "")) in TERMINAL:
            break
        operation = client.advance(operation_id, magnet_uri)
        if str(operation.get("phase", "")) not in TERMINAL:
            sleeper(max(1, min(int(operation.get("retryAfterSeconds", 10)), 60)))
    phase = str(operation.get("phase", ""))
    if phase != "READY":
        raise RuntimeError(f"PikPak operation did not become ready: {phase or 'UNKNOWN'}")
    items = operation.get("items")
    if not isinstance(items, list) or len(items) > 10000:
        raise RuntimeError("PikPak Connector returned invalid items")
    safe_items = [{"remoteFileId": str(item["remoteFileId"]), "relativePath": str(item["relativePath"]),
                   "sizeBytes": int(item["sizeBytes"]),
                   "storageProviderId": str(UUID(str(item["storageProviderId"]))),
                   "storagePath": str(item["storagePath"])} for item in items]
    children = []
    maximum_bytes = int(parameters.get("maxBytesPerItem", 20 * 1024 * 1024 * 1024))
    for index, item in enumerate(safe_items, start=1):
        file_name = Path(item["relativePath"]).name
        item_id = f"pikpak:{operation_id}:{index}"
        child = context.create_child("download_remote_storage_object", {
            "downloadRequestId": request_id, "itemId": item_id,
            "sourceProviderId": item["storageProviderId"], "sourcePath": item["storagePath"],
            "fileName": file_name, "expectedSize": item["sizeBytes"], "maxBytes": maximum_bytes,
            "destinationRelativePath": item["relativePath"],
            "destinationRootName": str(parameters.get("destinationRootName") or "downloads"),
            "ownerId": int(parameters.get("ownerId") or 0),
            "assetSourceBusinessId": f"{request_id}:{item_id}"},
            f"pikpak-object:{request_id}:{operation_id}:"
            f"{hashlib.sha256(item['remoteFileId'].encode()).hexdigest()}",
            business_type="DOWNLOAD_REQUEST", business_id=request_id)
        children.append(child)
    wait_all_or_cancel(context, children, 6900)
    return {"requestId": request_id, "operationId": operation_id, "status": "READY", "items": safe_items,
            "childTaskIds": [child.id for child in children]}

def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":")); temporary = Path(handle.name)
    temporary.replace(target)

def main() -> None:
    """执行 PikPak magnet 父任务。"""
    context = TaskContext.load()
    client = ConnectorClient(os.getenv("PIKPAK_CONNECTOR_URL", "http://127.0.0.1:23280"), os.environ["PIKPAK_CONNECTOR_TOKEN"])
    write_result(execute(context, client))

if __name__ == "__main__":
    main()
