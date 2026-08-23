#!/usr/bin/env python3
"""Delete eligible chapter cache entries through bounded Reader API calls."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen

MAX_BATCH_CALLS = 1_000_000


def call(url: str, token: str, opener=urlopen) -> dict:
    """Call one authenticated Reader maintenance endpoint."""
    request = Request(url, data=b"", method="POST",
                      headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
    with opener(request, timeout=60) as response:
        value = json.loads(response.read().decode("utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError("Reader Service returned an invalid maintenance response")
    return value


def execute(maintenance_id: str, base_url: str, token: str, opener=urlopen) -> dict:
    """Repeat bounded deletion batches until no eligible entry remains."""
    if not token:
        raise ValueError("Reader Service internal token is missing")
    root = base_url.rstrip("/") + f"/api/internal/v1/cache-maintenance/{maintenance_id}"
    deleted_total = 0
    for _ in range(MAX_BATCH_CALLS):
        result = call(root + "/batches", token, opener)
        deleted = int(result["deleted"])
        deleted_total = int(result["deletedTotal"])
        if deleted == 0:
            query = urlencode({"status": "SUCCEEDED"})
            finished = call(root + "/finish?" + query, token, opener)
            if finished.get("status") != "SUCCEEDED":
                raise RuntimeError("Reader Service did not confirm cache maintenance completion")
            return {"maintenanceId": maintenance_id, "status": "SUCCEEDED",
                    "deletedTotal": deleted_total}
    raise RuntimeError("Reader cache maintenance exceeded maximum batch calls")


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one chapter cache maintenance task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    write_result(execute(str(context["parameters"]["maintenanceId"]),
                         os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                         os.environ.get("READER_INTERNAL_TOKEN", "")))


if __name__ == "__main__":
    main()
