#!/usr/bin/env python3
"""将有界生成文本作为托管下载结果发布。"""

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

MAX_TEXT_BYTES = 2 * 1024 * 1024
SAFE_NAME = re.compile(r"^[^/\\\x00]{1,255}$")


def execute(parameters: dict, work_dir: Path, client: StorageGatewayClient) -> dict:
    """校验 UTF-8 文本并通过 Storage Gateway 幂等发布。"""
    request_id = str(parameters["downloadRequestId"])
    UUID(request_id)
    item_id = str(parameters["itemId"])
    file_name = str(parameters["fileName"] or "").strip()
    if not item_id or len(item_id) > 255:
        raise ValueError("itemId is invalid")
    if not SAFE_NAME.fullmatch(file_name) or file_name in {".", ".."}:
        raise ValueError("fileName is invalid")
    content = parameters["content"]
    if not isinstance(content, str) or not content.strip():
        raise ValueError("content must be nonblank text")
    encoded = content.encode("utf-8")
    if len(encoded) > MAX_TEXT_BYTES:
        raise ValueError("generated text exceeds limit")
    content_sha256 = hashlib.sha256(encoded).hexdigest()
    source = work_dir / "generated.txt"
    source.write_bytes(encoded)
    root_name = str(parameters.get("destinationRootName") or "downloads")
    relative_path = quote(request_id, safe="") + "/" + quote(file_name, safe="")
    storage_uri = client.publish(
        source, root_name, relative_path, f"download-text:{request_id}:{item_id}",
        len(encoded), content_sha256)
    return {"requestId": request_id, "itemId": item_id, "fileName": file_name,
            "storageUri": storage_uri, "sizeBytes": len(encoded),
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
    """执行一个生成文本发布任务。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                  os.getenv("STORAGE_GATEWAY_INTERNAL_TOKEN", ""))
    write_result(execute(context["parameters"], Path(os.environ["TASK_WORK_DIR"]), client))


if __name__ == "__main__":
    main()
