#!/usr/bin/env python3
"""Create a Download Ingestion child task through the Messaging boundary."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import urllib.request


def execute(job_id: str, base_url: str, token: str) -> dict:
    """Submit one opaque attachment job without exposing provider references."""
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
    return {"jobId": str(payload["jobId"]), "downloadRequestId": str(payload["downloadRequestId"]),
            "status": "SUBMITTED"}


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one attachment submission task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    write_result(execute(str(context["parameters"]["attachmentJobId"]),
                         os.getenv("MESSAGING_SERVICE_URL", "http://127.0.0.1:23250"),
                         os.environ.get("MESSAGING_INTERNAL_TOKEN", "")))


if __name__ == "__main__":
    main()
