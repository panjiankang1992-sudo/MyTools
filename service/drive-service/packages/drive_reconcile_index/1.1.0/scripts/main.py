#!/usr/bin/env python3
"""Bind Drive and Storage root-scan digests into one cutover report."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import tempfile
from urllib.request import Request, urlopen
from uuid import UUID

MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
DIGEST = re.compile(r"^[a-f0-9]{64}$")


def get_json(url: str, token: str, opener=urlopen) -> dict:
    """Read one bounded authenticated internal JSON document."""
    if not token:
        raise ValueError("Drive reconciliation token is missing")
    request = Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
    with opener(request, timeout=30) as response:
        body = response.read()
    if len(body) > 1024 * 1024:
        raise RuntimeError("Drive reconciliation response is too large")
    value = json.loads(body.decode("utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError("Drive reconciliation response is invalid")
    return value


def execute(parameters: dict, drive_url: str, drive_token: str,
            storage_url: str, storage_token: str, opener=urlopen) -> dict:
    """Verify scan ownership and compare strict count and digest fields."""
    migration_key = str(parameters["migrationKey"])
    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Drive reconciliation migration key is invalid")
    account_id = str(UUID(str(parameters["accountId"])))
    provider_id = str(UUID(str(parameters["storageProviderId"])))
    operation_id = str(UUID(str(parameters["storageOperationId"])))
    drive = get_json(drive_url.rstrip("/")
                     + f"/internal/v1/drive/migration/storage-accounts/{account_id}/digest",
                     drive_token, opener)
    operation = get_json(storage_url.rstrip("/")
                         + f"/api/internal/v1/storage/operations/{operation_id}",
                         storage_token, opener)
    if (str(operation.get("providerId")) != provider_id or operation.get("operationType") != "SCAN_ROOT"
            or operation.get("status") != "SUCCEEDED" or str(operation.get("sourcePath") or "") != ""):
        raise RuntimeError("Storage reconciliation operation is not a successful Provider root scan")
    storage = get_json(storage_url.rstrip("/")
                       + f"/api/internal/v1/storage/operations/{operation_id}/digest",
                       storage_token, opener)
    drive_count, drive_digest = digest_fields(drive)
    storage_count, storage_digest = digest_fields(storage)
    reasons = []
    if drive_count != storage_count:
        reasons.append("COUNT_MISMATCH")
    if drive_digest != storage_digest:
        reasons.append("CONTENT_MISMATCH")
    return {"migrationKey": migration_key, "accountId": account_id,
            "storageProviderId": provider_id, "storageOperationId": operation_id,
            "matched": not reasons, "mismatchReasons": reasons,
            "driveItemCount": drive_count, "storageItemCount": storage_count,
            "driveContentSha256": drive_digest, "storageContentSha256": storage_digest}


def digest_fields(value: dict) -> tuple[int, str]:
    """Validate the shared reconciliation digest contract."""
    count = value.get("itemCount")
    digest = str(value.get("contentSha256", ""))
    if not isinstance(count, int) or isinstance(count, bool) or count < 0 or not DIGEST.fullmatch(digest):
        raise RuntimeError("Drive reconciliation digest is invalid")
    return count, digest


def write_result(result: dict) -> None:
    """Atomically write the cutover evidence report."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one manual Drive index reconciliation task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    write_result(execute(context["parameters"],
                         os.getenv("DRIVE_SERVICE_URL", "http://127.0.0.1:23280"),
                         os.getenv("DRIVE_STORAGE_MIGRATION_TOKEN", ""),
                         os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                         os.getenv("STORAGE_INTERNAL_TOKEN", "")))


if __name__ == "__main__":
    main()
