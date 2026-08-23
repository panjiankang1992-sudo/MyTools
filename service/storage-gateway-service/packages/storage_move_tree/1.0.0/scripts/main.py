#!/usr/bin/env python3
"""Advance one server-owned, compensating remote move state machine."""

import json
import os
from pathlib import Path
import tempfile
import time
from urllib.request import Request, urlopen


def execute(operation_id: str, base_url: str, token: str, opener=urlopen,
            poll_seconds: float = 5.0, sleeper=time.sleep) -> dict:
    """Advance until the durable move reaches success or a stable failure."""
    endpoint = base_url.rstrip("/") + f"/api/internal/v1/storage/operations/{operation_id}/move/advance"
    while True:
        request = Request(endpoint, data=b"{}", method="POST",
                          headers={"Authorization": f"Bearer {token}",
                                   "Content-Type": "application/json"})
        with opener(request, timeout=60) as response:
            progress = json.loads(response.read().decode())
        if progress.get("finished"):
            if not progress.get("success"):
                raise RuntimeError("Storage remote move did not succeed")
            return {"operationId": operation_id, "status": "SUCCEEDED"}
        sleeper(poll_seconds)


def main() -> None:
    """Run one remote move task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result = execute(str(context["parameters"]["operationId"]),
                     os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                     os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


if __name__ == "__main__":
    main()
