#!/usr/bin/env python3
"""补偿原生复制目标并同步特殊步骤终态。"""

import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen
from uuid import UUID

STATUS_BY_STEP = {"on_failure": "FAILED", "on_timeout": "TIMED_OUT", "on_cancel": "CANCELLED"}


def execute(context: dict, base_url: str, token: str, opener=urlopen) -> dict:
    """先幂等删除未确认目标，再设置匹配的操作终态。"""
    status = STATUS_BY_STEP.get(str(context.get("stepName")))
    if status is None:
        raise ValueError("storage abort step kind is invalid")
    operation_id = str(UUID(str(context["parameters"]["operationId"])))
    headers = {"Authorization": f"Bearer {token}"}
    delete = Request(base_url.rstrip("/") +
                     f"/api/internal/v1/storage/operations/{operation_id}/native-copy/target",
                     method="DELETE", headers=headers)
    delete_failed = False
    try:
        with opener(delete, timeout=60):
            pass
    except Exception:
        delete_failed = True
    error_code = "STORAGE_NATIVE_COMPENSATION_FAILED" if delete_failed else f"STORAGE_TASK_{status}"
    body = json.dumps({"status": status, "errorCode": error_code}, separators=(",", ":")).encode()
    finish = Request(base_url.rstrip("/") +
                     f"/api/internal/v1/storage/operations/{operation_id}/finish",
                     data=body, method="POST", headers=headers | {"Content-Type": "application/json"})
    with opener(finish, timeout=30) as response:
        result = json.loads(response.read().decode("utf-8"))
    return {"status": result["status"]}


def main() -> None:
    """执行原生复制特殊步骤。"""
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
