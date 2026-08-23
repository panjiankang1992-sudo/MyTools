#!/usr/bin/env python3
"""Migrate Drive account references into Storage Gateway Providers."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def request_json(base_url: str, path: str, token: str, method: str = "GET",
                 payload: dict | None = None, opener=urlopen):
    """Call one bounded internal JSON endpoint."""
    body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
    request = Request(base_url.rstrip("/") + path, data=body, method=method,
                      headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with opener(request, timeout=30) as response:
        data = response.read()
        return {} if not data else json.loads(data.decode())


def execute(drive_url: str, migration_token: str, drive_token: str,
            storage_url: str, storage_token: str, opener=urlopen) -> dict:
    """Page sanitized accounts, register Providers, and idempotently bind them."""
    after_id = None
    processed = 0
    bound = 0
    while True:
        query = {"limit": 100}
        if after_id:
            query["afterId"] = after_id
        page = request_json(drive_url, "/internal/v1/drive/migration/storage-accounts?" + urlencode(query),
                            migration_token, opener=opener)
        for account in page.get("items") or []:
            account_id = str(account["id"])
            provider = request_json(storage_url, "/api/internal/v1/storage/providers", storage_token, "POST", {
                "name": "drive_" + account_id.replace("-", ""),
                "providerType": "RCLONE",
                "remoteKey": str(account["remoteKey"]),
                "secretRef": str(account["providerSecretRef"]),
                "enabled": bool(account["enabled"]),
            }, opener)
            request_json(drive_url, f"/internal/v1/drive/accounts/{account_id}/storage-provider",
                         drive_token, "PUT", {"storageProviderId": str(provider["id"])}, opener)
            processed += 1
            bound += 1
        after_id = page.get("nextAfterId")
        if not after_id:
            break
    return {"processed": processed, "bound": bound}


def write_result(result: dict) -> None:
    """Atomically write the migration summary."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute the manual Drive Provider migration task."""
    write_result(execute(
        os.getenv("DRIVE_SERVICE_URL", "http://127.0.0.1:23280"),
        os.getenv("DRIVE_STORAGE_MIGRATION_TOKEN", ""), os.getenv("DRIVE_INTERNAL_TOKEN", ""),
        os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
        os.getenv("STORAGE_INTERNAL_TOKEN", "")))


if __name__ == "__main__":
    main()
