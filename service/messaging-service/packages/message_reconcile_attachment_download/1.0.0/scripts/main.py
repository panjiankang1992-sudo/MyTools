#!/usr/bin/env python3
"""通过 Messaging 边界把附件下载对账到终态。"""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import time
import urllib.error
import urllib.request


TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}


def read_status(job_id: str, base_url: str, token: str) -> dict:
    """读取一次不包含来源 Secret 的附件状态。"""
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/internal/v1/attachment-downloads/{job_id}",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not isinstance(payload, dict) or str(payload.get("id")) != job_id:
        raise RuntimeError("Messaging Service returned an invalid attachment status")
    return payload


def execute(job_id: str, base_url: str, token: str, max_checks: int = 120,
            interval_seconds: int = 10, sleeper=time.sleep) -> dict:
    """有界轮询终态，并把临时网络失败留给后续检查。"""
    if not token:
        raise ValueError("Messaging Service internal token is missing")
    maximum = max(1, min(int(max_checks), 180))
    for check in range(1, maximum + 1):
        try:
            payload = read_status(job_id, base_url, token)
            status = str(payload.get("status") or "")
            if status in TERMINAL:
                return {"jobId": job_id,
                        "downloadRequestId": payload.get("downloadRequestId"),
                        "status": status, "lastErrorCode": payload.get("lastErrorCode"),
                        "checks": check}
        except (OSError, urllib.error.URLError, TimeoutError):
            if check == maximum:
                raise
        if check < maximum:
            sleeper(max(1, min(int(interval_seconds), 60)))
    raise TimeoutError("Attachment download did not reach a terminal state")


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
    """执行附件终态对账。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    write_result(execute(str(context["parameters"]["attachmentJobId"]),
                         os.getenv("MESSAGING_SERVICE_URL", "http://127.0.0.1:23250"),
                         os.environ.get("MESSAGING_INTERNAL_TOKEN", "")))


if __name__ == "__main__":
    main()
