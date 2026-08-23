#!/usr/bin/env python3
"""Compute one opaque managed object's digest through the node-local Storage Gateway."""

import hashlib
import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen


def execute(operation_id: str, base_url: str, token: str, opener=urlopen) -> dict:
    """Stream, hash and durably reconcile one checksum operation."""
    endpoint = base_url.rstrip("/") + f"/api/internal/v1/storage/checksum-operations/{operation_id}"
    request = Request(endpoint + "/content", headers={"Authorization": f"Bearer {token}"})
    digest = hashlib.sha256()
    size = 0
    with opener(request, timeout=60) as response:
        while True:
            chunk = response.read(64 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            size += len(chunk)
    result = {"status": "SUCCEEDED", "sizeBytes": size, "contentSha256": digest.hexdigest()}
    finish = Request(endpoint + "/finish", data=json.dumps(result, separators=(",", ":")).encode(),
                     method="POST", headers={"Authorization": f"Bearer {token}",
                                               "Content-Type": "application/json"})
    with opener(finish, timeout=30) as response:
        document = json.loads(response.read().decode())
    if document.get("status") != "SUCCEEDED":
        raise RuntimeError("Storage checksum reconciliation failed")
    return {"checksumOperationId": operation_id, **result}


def main() -> None:
    """Run one checksum task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result = execute(str(context["parameters"]["checksumOperationId"]),
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
