#!/usr/bin/env python3
"""Build and atomically publish a frozen Reader library generation."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen

MAX_BATCH_CALLS = 1_000_000


def call(url: str, token: str, opener=urlopen) -> dict:
    """Call one authenticated Reader rebuild endpoint."""
    request = Request(url, data=b"", method="POST",
                      headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
    with opener(request, timeout=60) as response:
        value = json.loads(response.read().decode("utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError("Reader Service returned an invalid rebuild response")
    return value


def execute(rebuild_id: str, base_url: str, token: str, opener=urlopen) -> dict:
    """Write bounded batches and publish only after the frozen scan completes."""
    if not token:
        raise ValueError("Reader Service internal token is missing")
    root = base_url.rstrip("/") + f"/api/internal/v1/library-rebuilds/{rebuild_id}"
    indexed_total = 0
    for _ in range(MAX_BATCH_CALLS):
        result = call(root + "/batches", token, opener)
        indexed_total = int(result["indexedTotal"])
        if bool(result["done"]):
            published = call(root + "/publish", token, opener)
            if published.get("status") != "SUCCEEDED":
                raise RuntimeError("Reader Service did not publish library generation")
            return {"rebuildId": rebuild_id, "status": "SUCCEEDED", "indexedTotal": indexed_total}
    raise RuntimeError("Reader library rebuild exceeded maximum batch calls")


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one Reader library rebuild task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    write_result(execute(str(context["parameters"]["rebuildId"]),
                         os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                         os.environ.get("READER_INTERNAL_TOKEN", "")))


if __name__ == "__main__":
    main()
