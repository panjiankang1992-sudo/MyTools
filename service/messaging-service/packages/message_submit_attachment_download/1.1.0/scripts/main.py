#!/usr/bin/env python3
"""创建附件下载，并把终态对账拆成独立子任务。"""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import urllib.request

from mytools_task_sdk.context import TaskContext


def submit(job_id: str, base_url: str, token: str) -> dict:
    """只用不透明附件作业标识提交下载。"""
    if not token:
        raise ValueError("Messaging Service internal token is missing")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/internal/v1/attachment-downloads/{job_id}/execute",
        data=b"", headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
        method="POST")
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not isinstance(payload, dict) or payload.get("status") != "SUBMITTED":
        raise RuntimeError("Messaging Service did not confirm attachment submission")
    return payload


def execute(task: TaskContext, base_url: str, token: str) -> dict:
    """提交下载并幂等创建终态对账子任务。"""
    job_id = str(task.parameters["attachmentJobId"])
    payload = submit(job_id, base_url, token)
    child = task.create_child(
        "message_reconcile_attachment_download", {"attachmentJobId": job_id},
        f"message-attachment-reconcile:{job_id}:v1",
        business_type="MESSAGE_ATTACHMENT", business_id=job_id)
    return {"jobId": str(payload["jobId"]),
            "downloadRequestId": str(payload["downloadRequestId"]),
            "reconciliationTaskId": child.id, "status": "SUBMITTED"}


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
    """执行附件提交任务。"""
    task = TaskContext.load()
    write_result(execute(task,
                         os.getenv("MESSAGING_SERVICE_URL", "http://127.0.0.1:23250"),
                         os.environ.get("MESSAGING_INTERNAL_TOKEN", "")))


if __name__ == "__main__":
    main()
