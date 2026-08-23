#!/usr/bin/env python3
"""在内容复验步骤成功后幂等完成原生复制操作。"""

import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen
from uuid import UUID


def execute(context: dict, base_url: str, token: str, opener=urlopen) -> dict:
    """将操作设置为成功终态。"""
    operation_id = str(UUID(str(context["parameters"]["operationId"])))
    body = json.dumps({"status": "SUCCEEDED", "errorCode": None}, separators=(",", ":")).encode()
    request = Request(base_url.rstrip("/") +
                      f"/api/internal/v1/storage/operations/{operation_id}/finish",
                      data=body, method="POST", headers={"Authorization": f"Bearer {token}",
                                                         "Content-Type": "application/json"})
    with opener(request, timeout=30) as response:
        result = json.loads(response.read().decode("utf-8"))
    return {"status": result["status"]}


def main() -> None:
    """执行原生复制成功终态步骤。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result = execute(context, os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                     os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


if __name__ == "__main__":
    main()
