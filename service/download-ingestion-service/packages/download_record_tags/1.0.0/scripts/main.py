#!/usr/bin/env python3
"""Persist the terminal media-intelligence tags for a downloaded item."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import urllib.request


def build_result(context: dict) -> dict:
    """Map an optional tag step output into a terminal download tag result."""
    parameters = context["parameters"]
    generated = (context.get("stepOutputs") or {}).get("generate_tags")
    if isinstance(generated, dict) and isinstance(generated.get("tags"), list) and generated["tags"]:
        return {"itemId": str(parameters["itemId"]), "tagStatus": "TAGGED",
                "tags": generated["tags"][:6]}
    return {"itemId": str(parameters["itemId"]), "tagStatus": "FAILED", "tags": []}


def record(base_url: str, token: str, request_id: str, result: dict,
           requester=urllib.request.urlopen) -> dict:
    """Submit an authenticated, idempotent tag callback."""
    if not token:
        raise ValueError("download internal token is missing")
    request = urllib.request.Request(
        base_url.rstrip("/") + f"/internal/v1/download-requests/{request_id}/tags",
        data=json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode(), method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with requester(request, timeout=30) as response:
        return json.loads(response.read().decode())


def write_result(result: dict) -> None:
    """Atomically publish the executor result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Record generated tags, or an explicit failed tag outcome, without losing the file."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result = build_result(context)
    write_result(record(os.getenv("DOWNLOAD_INGESTION_URL", "http://127.0.0.1:23220"),
                        os.getenv("DOWNLOAD_INTERNAL_TOKEN", ""),
                        str(context["parameters"]["downloadRequestId"]), result))


if __name__ == "__main__":
    main()
