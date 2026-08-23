#!/usr/bin/env python3
"""Synchronize Scheduler special-step state to one checksum operation."""

import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen

STATUS_BY_STEP = {"on_failure": "FAILED", "on_timeout": "TIMED_OUT", "on_cancel": "CANCELLED"}


def execute(context: dict, base_url: str, token: str, opener=urlopen) -> dict:
    """Write the matching terminal state idempotently."""
    status = STATUS_BY_STEP.get(str(context.get("stepName")))
    if status is None:
        raise ValueError("storage checksum finish step kind is invalid")
    operation_id = str(context["parameters"]["checksumOperationId"])
    body = json.dumps({"status": status, "errorCode": f"STORAGE_TASK_{status}"},
                      separators=(",", ":")).encode()
    request = Request(base_url.rstrip("/") +
                      f"/api/internal/v1/storage/checksum-operations/{operation_id}/finish",
                      data=body, method="POST", headers={"Authorization": f"Bearer {token}",
                                                         "Content-Type": "application/json"})
    with opener(request, timeout=30) as response:
        document = json.loads(response.read().decode())
    return {"status": document["status"]}


def main() -> None:
    """Run one checksum terminal hook."""
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
