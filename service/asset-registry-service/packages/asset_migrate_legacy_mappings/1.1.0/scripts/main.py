#!/usr/bin/env python3
"""Migrate a sealed legacy asset snapshot with target collection reconciliation."""

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
    """Read the sealed source and call protected Asset Registry migration APIs."""

    def __init__(self, legacy_url: str, legacy_token: str, asset_url: str,
                 asset_token: str, opener=urlopen):
        if not legacy_token or not asset_token:
            raise ValueError("Asset migration tokens are missing")
        self.legacy_url = legacy_url.rstrip("/")
        self.legacy_token = legacy_token
        self.asset_url = asset_url.rstrip("/")
        self.asset_token = asset_token
        self.opener = opener

    def page(self, source_snapshot_id: str, after_id: str | None) -> dict:
        """Read one stable source page."""

        query = {"snapshotId": source_snapshot_id, "limit": PAGE_SIZE}
        if after_id:
            query["afterId"] = after_id
        return self._request(self.legacy_url + "/internal/v1/migration/assets?" + urlencode(query),
                             "GET", None, self.legacy_token)

    def import_batch(self, migration_key: str, source_snapshot_id: str,
                     dry_run: bool, items: list[dict]) -> dict:
        """Validate or import one bounded mapping batch."""

        return self._request(self.asset_url
                             + "/internal/v1/assets/migrations/legacy-mappings/batches",
                             "POST", {"migrationKey": migration_key,
                                      "sourceSnapshotId": source_snapshot_id, "dryRun": dry_run,
                                      "items": items}, self.asset_token)

    def evidence(self, migration_key: str, source_snapshot_id: str) -> dict:
        """Read independently recomputed committed mapping evidence."""

        query = urlencode({"migrationKey": migration_key, "sourceSnapshotId": source_snapshot_id})
        return self._request(self.asset_url
                             + "/internal/v1/assets/migrations/legacy-mappings/evidence?" + query,
                             "GET", None, self.asset_token)

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
    """Migrate all pages and prove the committed target collection is identical."""

    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Asset migration key is invalid")
    if not MIGRATION_KEY.fullmatch(source_snapshot_id):
        raise ValueError("Asset source snapshot id is invalid")
    totals = {"exported": 0, "accepted": 0, "skipped": 0, "rejected": 0}
    identities: list[tuple[str, str, str]] = []
    after_id = start_after_id
    last_after_id = start_after_id
    while True:
        page = client.page(source_snapshot_id, after_id)
        if page.get("snapshotId") != source_snapshot_id:
            raise RuntimeError("Legacy asset snapshot changed during migration")
        items = page.get("items")
        if not isinstance(items, list) or len(items) > PAGE_SIZE:
            raise RuntimeError("Legacy asset page is invalid")
        if items:
            mapping_items = [mapping_item(item) for item in items]
            result = client.import_batch(migration_key, source_snapshot_id, dry_run, mapping_items)
            verify_batch(result, dry_run, len(mapping_items))
            totals["exported"] += len(mapping_items)
            for name in ("accepted", "skipped", "rejected"):
                totals[name] += int(result[name])
            identities.extend((item["sourceSystem"], item["legacyAssetId"], item_digest(item))
                              for item in mapping_items)
        next_after_id = page.get("nextAfterId")
        if next_after_id is None:
            break
        next_after_id = str(next_after_id)
        if not next_after_id or next_after_id == after_id:
            raise RuntimeError("Legacy asset cursor did not advance")
        after_id = next_after_id
        last_after_id = next_after_id[:255]
    digest = collection_digest(identities)
    if totals["exported"] != sum(totals[name] for name in ("accepted", "skipped", "rejected")):
        raise RuntimeError("Asset migration totals do not close")
    if not dry_run:
        evidence = client.evidence(migration_key, source_snapshot_id)
        if evidence.get("migrationKey") != migration_key \
                or evidence.get("sourceSnapshotId") != source_snapshot_id \
                or evidence.get("itemCount") != totals["exported"] \
                or evidence.get("collectionSha256") != digest:
            raise RuntimeError("Asset migration target reconciliation failed")
    return {"migrationKey": migration_key, "dryRun": dry_run,
            "sourceSnapshotId": source_snapshot_id, **totals,
            "digestSha256": digest, "lastAfterId": last_after_id}


def mapping_item(item: object) -> dict:
    """Strip domain metadata and require the exact mapping contract."""

    if not isinstance(item, dict) or not isinstance(item.get("asset"), dict):
        raise RuntimeError("Legacy asset item is invalid")
    source = item.get("sourceSystem")
    legacy_id = item.get("legacyAssetId")
    if not isinstance(source, str) or not source or not isinstance(legacy_id, str) or not legacy_id:
        raise RuntimeError("Legacy asset identity is invalid")
    return {"sourceSystem": source, "legacyAssetId": legacy_id, "asset": item["asset"]}


def item_digest(item: dict) -> str:
    """Calculate the Asset Registry-compatible mapping payload digest."""

    asset = item["asset"]
    required = ("ownerId", "idempotencyKey", "sourceType", "sourceBusinessId",
                "contentSha256", "sizeBytes", "mimeType")
    if any(name not in asset for name in required):
        raise RuntimeError("Legacy asset payload is invalid")
    digest = hashlib.sha256()
    update_digest(digest, item["sourceSystem"], item["legacyAssetId"], asset["ownerId"],
                  asset["idempotencyKey"], asset["sourceType"], asset["sourceBusinessId"],
                  str(asset["contentSha256"]).lower(), asset["sizeBytes"], asset["mimeType"])
    location = asset.get("location")
    if location is not None:
        if not isinstance(location, dict) or any(name not in location for name in
                                                ("idempotencyKey", "providerType", "storageUri")):
            raise RuntimeError("Legacy asset location is invalid")
        update_digest(digest, location["idempotencyKey"], location["providerType"],
                      location["storageUri"], location.get("providerVersion"))
    return digest.hexdigest()


def collection_digest(identities: list[tuple[str, str, str]]) -> str:
    """Hash mapping payload digests in target identity order."""

    digest = hashlib.sha256()
    for _source, _legacy_id, payload_sha256 in sorted(identities):
        update_digest(digest, payload_sha256)
    return digest.hexdigest()


def update_digest(digest, *values: object) -> None:
    """Use the shared length-prefixed SHA-256 protocol."""

    for value in values:
        encoded = ("" if value is None else str(value)).encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)


def verify_batch(result: dict, dry_run: bool, size: int) -> None:
    """Require a closing bounded target batch report."""

    if result.get("dryRun") is not dry_run or not DIGEST.fullmatch(
            str(result.get("digestSha256", ""))):
        raise RuntimeError("Asset migration batch evidence is invalid")
    values = [result.get(name) for name in ("accepted", "skipped", "rejected")]
    if any(not isinstance(value, int) or isinstance(value, bool) or value < 0 for value in values) \
            or sum(values) != size:
        raise RuntimeError("Asset migration batch counts do not close")


def write_result(result: dict) -> None:
    """Atomically write the non-sensitive task report."""

    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
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
    write_result(execute(client, str(parameters["migrationKey"]),
                         str(parameters["sourceSnapshotId"]), bool(parameters["dryRun"]),
                         parameters.get("afterId")))


if __name__ == "__main__":
    main()
