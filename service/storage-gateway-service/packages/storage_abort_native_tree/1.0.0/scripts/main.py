#!/usr/bin/env python3
"""Cancel active native-tree children and persist the parent terminal state."""

import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen
from uuid import UUID

STATUS_BY_STEP = {"on_failure": "FAILED", "on_timeout": "TIMED_OUT", "on_cancel": "CANCELLED"}


def execute(context: dict, base_url: str, token: str, opener=urlopen) -> dict:
    """Map the special step to one idempotent parent abort call."""
    status = STATUS_BY_STEP.get(str(context.get("stepName")))
    if status is None:
        raise ValueError("native tree abort step is invalid")
    operation_id = str(UUID(str(context["parameters"]["operationId"])))
    body = json.dumps({"status": status, "errorCode": f"STORAGE_TASK_{status}"},
                      separators=(",", ":")).encode()
    request = Request(base_url.rstrip("/") +
                      f"/api/internal/v1/storage/operations/{operation_id}/native-tree/abort",
                      data=body, method="POST",
                      headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with opener(request, timeout=90) as response:
        result = json.loads(response.read().decode())
    return {"status": result["status"]}


def main() -> None:
    """Run one native-tree special step."""
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
