#!/usr/bin/env python3
"""校验并复制一个 Storage Gateway 托管对象。"""

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
    """受限读取、校验并幂等发布一个托管对象。"""
    request_id = str(parameters["downloadRequestId"])
    UUID(request_id)
    item_id = str(parameters["itemId"])
    file_name = str(parameters["fileName"] or "").strip()
    if not item_id or len(item_id) > 255:
        raise ValueError("itemId is invalid")
    if not SAFE_NAME.fullmatch(file_name) or file_name in {".", ".."}:
        raise ValueError("fileName is invalid")
    maximum = int(parameters.get("maxBytes", DEFAULT_MAX_BYTES))
    if maximum <= 0 or maximum > MAX_CONFIGURED_BYTES:
        raise ValueError("maxBytes is outside the supported range")
    source_uri = str(parameters["sourceStorageUri"])
    temporary = work_dir / "source.bin"
    size = client.download(source_uri, temporary, maximum)
    digest = hashlib.sha256()
    with temporary.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    content_sha256 = digest.hexdigest()
    expected = str(parameters.get("expectedSha256") or "").lower()
    if expected and expected != content_sha256:
        raise ValueError("storage object checksum mismatch")
    root_name = str(parameters.get("destinationRootName") or "downloads")
    relative_path = quote(request_id, safe="") + "/" + quote(file_name, safe="")
    storage_uri = client.publish(
        temporary, root_name, relative_path,
        f"download-storage:{request_id}:{item_id}", size, content_sha256)
    return {"requestId": request_id, "itemId": item_id, "fileName": file_name,
            "storageUri": storage_uri, "sizeBytes": size,
            "contentSha256": content_sha256}


def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行一个托管对象下载任务。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23260"),
                                  os.getenv("STORAGE_GATEWAY_INTERNAL_TOKEN", ""))
    work_dir = Path(os.environ["TASK_WORK_DIR"])
    write_result(execute(context["parameters"], work_dir, client))


if __name__ == "__main__":
    main()
