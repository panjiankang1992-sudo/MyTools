#!/usr/bin/env python3
"""从 Storage Gateway 远端 Provider 读取并发布一个受管对象。"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote
from uuid import UUID
from mytools_task_sdk.storage import StorageGatewayClient

DEFAULT_MAX_BYTES = 20 * 1024 * 1024 * 1024
MAX_CONFIGURED_BYTES = 100 * 1024 * 1024 * 1024
SAFE_NAME = re.compile(r"^[^/\\\x00]{1,255}$")

def execute(parameters: dict, work_dir: Path, client: StorageGatewayClient) -> dict:
    """受限读取远端对象、校验摘要并幂等发布。"""
    request_id = str(UUID(str(parameters["downloadRequestId"])))
    provider_id = str(UUID(str(parameters["sourceProviderId"])))
    item_id = str(parameters["itemId"])
    file_name = str(parameters.get("fileName") or "").strip()
    source_path = str(parameters.get("sourcePath") or "").strip()
    if not item_id or len(item_id) > 255:
        raise ValueError("itemId is invalid")
    if not SAFE_NAME.fullmatch(file_name) or file_name in {".", ".."}:
        raise ValueError("fileName is invalid")
    if not source_path or source_path.startswith("/") or "\\" in source_path or ".." in source_path.split("/"):
        raise ValueError("sourcePath is invalid")
    maximum = int(parameters.get("maxBytes", DEFAULT_MAX_BYTES))
    if maximum <= 0 or maximum > MAX_CONFIGURED_BYTES:
        raise ValueError("maxBytes is outside the supported range")
    temporary = work_dir / "remote-source.bin"
    size = client.download_remote(provider_id, source_path, temporary, maximum)
    digest = hashlib.sha256()
    with temporary.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    content_sha256 = digest.hexdigest()
    expected_size = parameters.get("expectedSize")
    if expected_size is not None and int(expected_size) != size:
        raise ValueError("remote storage object size mismatch")
    root_name = str(parameters.get("destinationRootName") or "downloads")
    destination_path = str(parameters.get("destinationRelativePath") or file_name).strip()
    parts = destination_path.split("/")
    if not parts or any(not part or part in {".", ".."} or "\\" in part or "\x00" in part for part in parts):
        raise ValueError("destinationRelativePath is invalid")
    relative_path = quote(request_id, safe="") + "/" + "/".join(quote(part, safe="") for part in parts)
    storage_uri = client.publish(temporary, root_name, relative_path,
        f"download-remote:{request_id}:{item_id}", size, content_sha256)
    return {"requestId": request_id, "itemId": item_id, "fileName": file_name,
            "storageUri": storage_uri, "sizeBytes": size, "contentSha256": content_sha256}

def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":")); temporary = Path(handle.name)
    temporary.replace(target)

def main() -> None:
    """执行一个远端存储对象下载任务。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                  os.environ["STORAGE_GATEWAY_INTERNAL_TOKEN"])
    write_result(execute(context["parameters"], Path(os.environ["TASK_WORK_DIR"]), client))

if __name__ == "__main__":
    main()
