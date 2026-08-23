#!/usr/bin/env python3
"""Migrate sanitized Drive Provider references with dry-run reconciliation."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlencode
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from uuid import UUID

PAGE_SIZE = 100
ALLOWED_FIELDS = frozenset({"id", "remoteKey", "providerSecretRef", "enabled",
                            "providerType", "endpointUri", "regionName"})
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


class Client:
    """Call protected Drive export, Storage registration, and Drive binding APIs."""

    def __init__(self, drive_url: str, migration_token: str, drive_token: str,
                 storage_url: str, storage_token: str, opener=urlopen):
        """Create a client only when all isolated internal credentials are present."""
        if not migration_token:
            raise ValueError("Storage Provider migration read token is missing")
        self.drive_url = drive_url.rstrip("/")
        self.migration_token = migration_token
        self.drive_token = drive_token
        self.storage_url = storage_url.rstrip("/")
        self.storage_token = storage_token
        self.opener = opener

    def page(self, after_id: str | None) -> dict:
        """Read one stable sanitized account page."""
        query = {"limit": PAGE_SIZE}
        if after_id:
            query["afterId"] = after_id
        return self._request(self.drive_url + "/internal/v1/drive/migration/storage-accounts?"
                             + urlencode(query), "GET", None, self.migration_token)

    def register(self, payload: dict) -> str:
        """Idempotently register one Storage Provider and return its UUID."""
        if not self.storage_token:
            raise ValueError("Storage Provider migration write token is missing")
        result = self._request(self.storage_url + "/api/internal/v1/storage/providers",
                               "POST", payload, self.storage_token)
        provider_id = str(result.get("id", ""))
        if not provider_id:
            raise RuntimeError("Storage Provider registration returned no id")
        return provider_id

    def bind(self, account_id: str, provider_id: str) -> None:
        """Idempotently bind one Drive account to its Storage Provider."""
        if not self.drive_token:
            raise ValueError("Drive Provider binding token is missing")
        self._request(self.drive_url + f"/internal/v1/drive/accounts/{account_id}/storage-provider",
                      "PUT", {"storageProviderId": provider_id}, self.drive_token)

    def _request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            body = response.read()
        value = {} if not body else json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise RuntimeError("Provider migration endpoint returned an invalid response")
        return value


def normalize(account: dict) -> tuple[str, dict]:
    """Validate a sanitized export row and build the exact Provider request."""
    if not isinstance(account, dict) or not set(account).issubset(ALLOWED_FIELDS):
        raise ValueError("Legacy Provider row contains forbidden fields")
    try:
        account_id = str(UUID(str(account["id"])))
    except (ValueError, TypeError, AttributeError) as exception:
        raise ValueError("Legacy Provider account id is invalid") from exception
    if not isinstance(account["enabled"], bool):
        raise ValueError("Legacy Provider enabled flag is invalid")
    remote_key = str(account["remoteKey"])
    secret_ref = str(account["providerSecretRef"])
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", remote_key):
        raise ValueError("Legacy Provider remote key is invalid")
    if not re.fullmatch(r"(?:secret|vault|env)://[A-Za-z0-9._/:-]{1,500}", secret_ref):
        raise ValueError("Legacy Provider Secret reference is invalid")
    if len(account_id) != 36:
        raise ValueError("Legacy Provider account id is invalid")
    provider_type = str(account.get("providerType") or "RCLONE")
    if provider_type not in {"RCLONE", "WEBDAV", "S3"}:
        raise ValueError("Legacy Provider type is invalid")
    payload = {
        "name": "drive_" + account_id.replace("-", "").lower(),
        "providerType": provider_type,
        "remoteKey": remote_key,
        "secretRef": secret_ref,
        "enabled": account["enabled"],
    }
    endpoint = account.get("endpointUri")
    region = account.get("regionName")
    if provider_type == "RCLONE" and (endpoint or region):
        raise ValueError("Rclone Provider row contains native routing fields")
    if provider_type in {"WEBDAV", "S3"} and not endpoint:
        raise ValueError("Native Provider endpoint is missing")
    if provider_type == "S3" and not region:
        raise ValueError("S3 Provider region is missing")
    if endpoint:
        endpoint = str(endpoint).strip()
        parsed = urlparse(endpoint)
        loopback_http = parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost"}
        if (parsed.scheme != "https" and not loopback_http) or not parsed.hostname \
                or parsed.username or parsed.password or parsed.query or parsed.fragment or len(endpoint) > 2048:
            raise ValueError("Native Provider endpoint is invalid")
        payload["endpointUri"] = endpoint
    if region:
        region = str(region).strip()
        if not re.fullmatch(r"[a-z0-9-]{1,64}", region):
            raise ValueError("S3 Provider region is invalid")
        payload["regionName"] = region
    if provider_type == "S3" and (not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", remote_key)
                                  or ".." in remote_key or ".-" in remote_key or "-." in remote_key
                                  or re.fullmatch(r"[0-9]{1,3}(?:\.[0-9]{1,3}){3}", remote_key)):
        raise ValueError("S3 Provider bucket is invalid")
    return account_id, payload


def execute(client: Client, migration_key: str, dry_run: bool,
            start_after_id: str | None = None) -> dict:
    """Validate or migrate all pages and return a deterministic reconciliation report."""
    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Storage Provider migration key is invalid")
    totals = {"exported": 0, "accepted": 0, "bound": 0, "rejected": 0}
    digest = hashlib.sha256()
    after_id = start_after_id
    last_after_id = start_after_id
    while True:
        page = client.page(after_id)
        items = page.get("items")
        if not isinstance(items, list) or len(items) > PAGE_SIZE:
            raise RuntimeError("Legacy Provider migration page is invalid")
        for account in items:
            totals["exported"] += 1
            digest.update(json.dumps(account, sort_keys=True, separators=(",", ":")).encode())
            digest.update(b"\n")
            if isinstance(account, dict) and "id" in account:
                last_after_id = str(account["id"])[:255]
            try:
                account_id, payload = normalize(account)
            except (KeyError, TypeError, ValueError):
                totals["rejected"] += 1
                continue
            last_after_id = account_id
            totals["accepted"] += 1
            if not dry_run:
                provider_id = client.register(payload)
                client.bind(account_id, provider_id)
                totals["bound"] += 1
        next_after_id = page.get("nextAfterId")
        if next_after_id is None:
            break
        next_after_id = str(next_after_id)
        if not next_after_id or next_after_id == after_id:
            raise RuntimeError("Legacy Provider migration cursor did not advance")
        after_id = next_after_id
        last_after_id = next_after_id
    return {"migrationKey": migration_key, "dryRun": dry_run, **totals,
            "digestSha256": digest.hexdigest(), "lastAfterId": last_after_id}


def write_result(result: dict) -> None:
    """Atomically write the migration report."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run one explicit Provider migration task instance."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("DRIVE_SERVICE_URL", "http://127.0.0.1:23280"),
                    os.getenv("DRIVE_STORAGE_MIGRATION_TOKEN", ""),
                    os.getenv("DRIVE_INTERNAL_TOKEN", ""),
                    os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                    os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    start_after_id = parameters.get("afterId")
    write_result(execute(client, str(parameters["migrationKey"]), bool(parameters["dryRun"]),
                         None if start_after_id is None else str(start_after_id)))


if __name__ == "__main__":
    main()
