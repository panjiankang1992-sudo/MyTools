#!/usr/bin/env python3
"""Retry a persisted remote move recovery action until it converges."""

import json
import os
from pathlib import Path
import tempfile
import time
from urllib.request import Request, urlopen


def execute(operation_id: str, base_url: str, token: str, opener=urlopen,
            poll_seconds: float = 10.0, sleeper=time.sleep, context: dict | None = None) -> dict:
    """Poll the server-owned recovery action to completion."""
    endpoint = base_url.rstrip("/") + f"/api/internal/v1/storage/operations/{operation_id}/move/recover"
    while True:
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        if context is not None:
            headers |= {"X-Task-Instance-Id": str(context["taskInstanceId"]),
                        "X-Task-Step-Name": str(context["stepName"]),
                        "X-Task-Business-Key": operation_id,
                        "X-Task-Fencing-Token": str(context["fencingToken"])}
        request = Request(endpoint, data=b"{}", method="POST", headers=headers)
        with opener(request, timeout=60) as response:
            progress = json.loads(response.read().decode())
        if progress.get("finished"):
            if not progress.get("success"):
                raise RuntimeError("Storage move recovery did not converge")
            return {"operationId": operation_id, "status": "RECOVERED"}
        sleeper(poll_seconds)


def main() -> None:
    """Run one remote move recovery task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result = execute(str(context["parameters"]["operationId"]),
                     os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                     os.getenv("STORAGE_INTERNAL_TOKEN", ""), context=context)
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


if __name__ == "__main__":
    main()
