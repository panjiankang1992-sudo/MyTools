#!/usr/bin/env python3
"""Compensate a remote move or mark it for forward recovery."""

import json
import os
from pathlib import Path
import tempfile
import time
from urllib.request import Request, urlopen

STATUS_BY_STEP = {"on_failure": "FAILED", "on_timeout": "TIMED_OUT", "on_cancel": "CANCELLED"}


def request_json(url: str, body: dict, token: str, opener) -> dict:
    """POST one authenticated JSON request."""
    request = Request(url, data=json.dumps(body, separators=(",", ":")).encode(), method="POST",
                      headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with opener(request, timeout=30) as response:
        return json.loads(response.read().decode())


def execute(context: dict, base_url: str, token: str, opener=urlopen, attempts: int = 12,
            poll_seconds: float = 5.0, sleeper=time.sleep) -> dict:
    """Attempt bounded compensation before persisting a recovery marker."""
    status = STATUS_BY_STEP.get(str(context.get("stepName")))
    if status is None:
        raise ValueError("storage move abort step kind is invalid")
    operation_id = str(context["parameters"]["operationId"])
    root = base_url.rstrip("/") + f"/api/internal/v1/storage/operations/{operation_id}/move"
    progress = {}
    for _attempt in range(attempts):
        try:
            progress = request_json(root + "/abort", {"status": status}, token, opener)
            if progress.get("finished"):
                return {"status": status, "recoveryRequired": bool(progress.get("recoveryRequired"))}
        except Exception:
            # 后台任务过期或 RC 暂时不可用时，最终仍需持久化恢复动作。
            progress = {}
        sleeper(poll_seconds)
    progress = request_json(root + "/recovery-required", {}, token, opener)
    return {"status": status, "recoveryRequired": bool(progress.get("recoveryRequired", True))}


def main() -> None:
    """Run one bounded move compensation hook."""
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
