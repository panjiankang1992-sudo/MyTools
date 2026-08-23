#!/usr/bin/env python3
"""Compare deterministic Drive and Storage scan digests."""

import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen


def get_json(url: str, token: str, opener=urlopen) -> dict:
    """Read one authenticated internal digest document."""
    request = Request(url, headers={"Authorization": f"Bearer {token}"})
    with opener(request, timeout=30) as response:
        return json.loads(response.read().decode())


def execute(parameters: dict, drive_url: str, drive_token: str,
            storage_url: str, storage_token: str, opener=urlopen) -> dict:
    """Build an explicit count and SHA-256 reconciliation result."""
    account_id = str(parameters["accountId"])
    operation_id = str(parameters["storageOperationId"])
    drive = get_json(drive_url.rstrip("/") +
                     f"/internal/v1/drive/migration/storage-accounts/{account_id}/digest",
                     drive_token, opener)
    storage = get_json(storage_url.rstrip("/") +
                       f"/api/internal/v1/storage/operations/{operation_id}/digest",
                       storage_token, opener)
    matched = int(drive["itemCount"]) == int(storage["itemCount"]) and \
        str(drive["contentSha256"]) == str(storage["contentSha256"])
    return {"accountId": account_id, "storageOperationId": operation_id,
            "matched": matched, "drive": drive, "storage": storage}


def main() -> None:
    """Execute one manual index reconciliation task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result = execute(context["parameters"],
                     os.getenv("DRIVE_SERVICE_URL", "http://127.0.0.1:23280"),
                     os.getenv("DRIVE_STORAGE_MIGRATION_TOKEN", ""),
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
