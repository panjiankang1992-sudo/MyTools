#!/usr/bin/env python3
"""Synchronize Scheduler terminal hooks to Reader cache maintenance."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen

STATUS_BY_STEP = {"on_failure": "FAILED", "on_timeout": "TIMED_OUT", "on_cancel": "CANCELLED"}


def execute(context: dict, base_url: str, token: str, opener=urlopen) -> dict:
    """Set the matching terminal maintenance state."""
    status = STATUS_BY_STEP.get(str(context.get("stepName")))
    if status is None:
        raise ValueError("Reader cache maintenance finish step is invalid")
    maintenance_id = str(context["parameters"]["maintenanceId"])
    query = urlencode({"status": status, "errorCode": f"READER_CACHE_TASK_{status}"})
    request = Request(base_url.rstrip("/") +
                      f"/api/internal/v1/cache-maintenance/{maintenance_id}/finish?{query}",
                      data=b"", method="POST", headers={"Authorization": f"Bearer {token}"})
    with opener(request, timeout=30) as response:
        result = json.loads(response.read().decode("utf-8"))
    return {"status": result["status"]}


def main() -> None:
    """Run one Reader cache maintenance terminal hook."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result = execute(context, os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                     os.getenv("READER_INTERNAL_TOKEN", ""))
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


if __name__ == "__main__":
    main()
