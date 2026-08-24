#!/usr/bin/env python3
"""Migrate normalized legacy assets through protected bounded APIs."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen

PAGE_SIZE = 200
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
DIGEST = re.compile(r"^[a-f0-9]{64}$")


class Client:
    """Read a legacy asset adapter and write Asset Registry migration batches."""

    def __init__(self, legacy_url: str, legacy_token: str, asset_url: str,
                 asset_token: str, opener=urlopen):
        """Create a client with isolated source and target credentials."""
        if not legacy_token or not asset_token:
            raise ValueError("Asset migration tokens are missing")
        self.legacy_url = legacy_url.rstrip("/")
        self.legacy_token = legacy_token
        self.asset_url = asset_url.rstrip("/")
        self.asset_token = asset_token
        self.opener = opener

    def page(self, source_snapshot_id: str, after_id: str | None) -> dict:
        """Read one stable normalized legacy asset page."""
        query = {"snapshotId": source_snapshot_id, "limit": PAGE_SIZE}
        if after_id:
            query["afterId"] = after_id
        return self._request(self.legacy_url + "/internal/v1/migration/assets?" + urlencode(query),
                             "GET", None, self.legacy_token)

    def import_batch(self, migration_key: str, source_snapshot_id: str,
                     dry_run: bool, items: list[dict]) -> dict:
        """Validate or import one bounded mapping page."""
        return self._request(self.asset_url + "/internal/v1/assets/migrations/legacy-mappings/batches",
                             "POST", {"migrationKey": migration_key,
                                      "sourceSnapshotId": source_snapshot_id, "dryRun": dry_run,
                                      "items": items}, self.asset_token)

    def _request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            body = response.read()
        if len(body) > 16 * 1024 * 1024:
            raise RuntimeError("Asset migration response is too large")
        value = json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise RuntimeError("Asset migration endpoint returned invalid data")
        return value


def execute(client: Client, migration_key: str, source_snapshot_id: str, dry_run: bool,
            start_after_id: str | None = None) -> dict:
    """Migrate all stable pages and aggregate target evidence."""
    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Asset migration key is invalid")
    if not MIGRATION_KEY.fullmatch(source_snapshot_id):
        raise ValueError("Asset source snapshot id is invalid")
    totals = {"exported": 0, "accepted": 0, "skipped": 0, "rejected": 0}
    digest = hashlib.sha256()
    after_id = start_after_id
    last_after_id = start_after_id
    while True:
        page = client.page(source_snapshot_id, after_id)
        page_snapshot_id = page.get("snapshotId")
        if not isinstance(page_snapshot_id, str) or not page_snapshot_id or len(page_snapshot_id) > 128:
            raise RuntimeError("Legacy asset snapshot is invalid")
        if page_snapshot_id != source_snapshot_id:
            raise RuntimeError("Legacy asset snapshot changed during migration")
        items = page.get("items")
        if not isinstance(items, list) or len(items) > PAGE_SIZE:
            raise RuntimeError("Legacy asset page is invalid")
        if items:
            mapping_items = [{"sourceSystem": item.get("sourceSystem"),
                              "legacyAssetId": item.get("legacyAssetId"),
                              "asset": item.get("asset")} for item in items]
            result = client.import_batch(migration_key, page_snapshot_id, dry_run, mapping_items)
            if result.get("dryRun") is not dry_run:
                raise RuntimeError("Asset migration mode does not match")
            counts = [count(result, name) for name in ("accepted", "skipped", "rejected")]
            if sum(counts) != len(items):
                raise RuntimeError("Asset migration counts do not close")
            batch_digest = str(result.get("digestSha256", ""))
            if not DIGEST.fullmatch(batch_digest):
                raise RuntimeError("Asset migration digest is invalid")
            totals["exported"] += len(items)
            for name, value in zip(("accepted", "skipped", "rejected"), counts, strict=True):
                totals[name] += value
            digest.update(bytes.fromhex(batch_digest))
        next_after_id = page.get("nextAfterId")
        if next_after_id is None:
            break
        next_after_id = str(next_after_id)
        if not next_after_id or next_after_id == after_id:
            raise RuntimeError("Legacy asset cursor did not advance")
        after_id = next_after_id
        last_after_id = next_after_id[:255]
    return {"migrationKey": migration_key, "dryRun": dry_run,
            "sourceSnapshotId": source_snapshot_id, **totals,
            "digestSha256": digest.hexdigest(), "lastAfterId": last_after_id}


def count(result: dict, name: str) -> int:
    """Read one non-negative integer count without accepting booleans."""
    value = result.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise RuntimeError("Asset migration count is invalid")
    return value


def write_result(result: dict) -> None:
    """Atomically write the migration report."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run one explicit legacy asset migration task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("LEGACY_ASSET_ADAPTER_URL", "http://127.0.0.1:23330"),
                    os.getenv("LEGACY_ASSET_ADAPTER_TOKEN", ""),
                    os.getenv("ASSET_REGISTRY_URL", "http://127.0.0.1:23270"),
                    os.getenv("ASSET_REGISTRY_INTERNAL_TOKEN", ""))
    after_id = parameters.get("afterId")
    write_result(execute(client, str(parameters["migrationKey"]), str(parameters["sourceSnapshotId"]),
                         bool(parameters["dryRun"]),
                         None if after_id is None else str(after_id)))


if __name__ == "__main__":
    main()
