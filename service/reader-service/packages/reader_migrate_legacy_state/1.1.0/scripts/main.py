#!/usr/bin/env python3
"""Migrate one frozen legacy Reader state collection with target evidence."""

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
ENTITY_TYPES = ("SHELF", "PROGRESS", "MARKER")
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
DIGEST = re.compile(r"^[a-f0-9]{64}$")


class Client:
    """Call protected frozen source and Reader target endpoints."""

    def __init__(self, mytools_url: str, mytools_token: str, reader_url: str,
                 reader_token: str, opener=urlopen):
        if not mytools_token or not reader_token:
            raise ValueError("Reader migration tokens are missing")
        self.mytools_url = mytools_url.rstrip("/")
        self.mytools_token = mytools_token
        self.reader_url = reader_url.rstrip("/")
        self.reader_token = reader_token
        self.opener = opener

    def page(self, entity_type: str, owner_id: int, key: str,
             high_water: dict | None) -> dict:
        """Read one page constrained by a stable composite high water."""

        query = {"type": entity_type, "afterOwnerId": owner_id,
                 "afterKey": key, "limit": PAGE_SIZE}
        if high_water is not None:
            query.update({"snapshotOwnerId": high_water["ownerId"],
                          "snapshotKey": high_water["key"]})
        return self._request(self.mytools_url + "/internal/v1/migration/reader-state?"
                             + urlencode(query), "GET", None, self.mytools_token)

    def import_batch(self, migration_key: str, dry_run: bool, items: list[dict]) -> dict:
        """Validate or import one bounded target batch."""

        return self._request(self.reader_url
                             + "/api/internal/v1/migrations/legacy-reader/batches",
                             "POST", {"migrationKey": migration_key, "dryRun": dry_run,
                                      "items": items}, self.reader_token)

    def evidence(self, migration_key: str) -> dict:
        """Read independently recomputed committed target evidence."""

        return self._request(self.reader_url
                             + "/api/internal/v1/migrations/legacy-reader/evidence?"
                             + urlencode({"migrationKey": migration_key}),
                             "GET", None, self.reader_token)

    def _request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        data = None if payload is None else json.dumps(
            payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            body = response.read()
        if len(body) > 16 * 1024 * 1024:
            raise RuntimeError("Reader migration response is too large")
        value = json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise RuntimeError("Reader migration endpoint returned invalid data")
        return value


def execute(client: Client, migration_key: str, dry_run: bool,
            source_high_water: dict | None = None) -> dict:
    """Migrate all frozen entity sets and reconcile the committed collection."""

    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Reader migration key is invalid")
    high_waters = validate_high_waters(source_high_water)
    totals = {"exported": 0, "accepted": 0, "skipped": 0, "rejected": 0}
    identities: list[tuple[str, int, str, str]] = []
    for entity_type in ENTITY_TYPES:
        owner_id, key = 0, ""
        while True:
            page = client.page(entity_type, owner_id, key, high_waters.get(entity_type))
            items = page.get("items")
            current = {"ownerId": page.get("snapshotOwnerId"),
                       "key": page.get("snapshotKey")}
            validate_page(items, current)
            if entity_type in high_waters and current != high_waters[entity_type]:
                raise RuntimeError("Legacy Reader high water changed")
            high_waters[entity_type] = current
            if items:
                result = client.import_batch(migration_key, dry_run, items)
                verify_batch(result, len(items))
                totals["exported"] += len(items)
                for name in ("accepted", "skipped", "rejected"):
                    totals[name] += int(result[name])
                identities.extend(item_identity(item) for item in items)
            next_owner = page.get("nextAfterOwnerId")
            next_key = page.get("nextAfterKey")
            if not isinstance(next_owner, int) or isinstance(next_owner, bool) \
                    or not isinstance(next_key, str) or (items and (next_owner, next_key)
                                                         <= (owner_id, key)):
                raise RuntimeError("Legacy Reader migration cursor did not advance")
            owner_id, key = next_owner, next_key
            if page.get("complete") is True:
                break
    digest = collection_digest(identities)
    if totals["exported"] != sum(totals[name] for name in ("accepted", "skipped", "rejected")):
        raise RuntimeError("Reader migration totals do not close")
    if not dry_run:
        evidence = client.evidence(migration_key)
        if evidence.get("migrationKey") != migration_key \
                or evidence.get("itemCount") != totals["exported"] \
                or evidence.get("digestSha256") != digest:
            raise RuntimeError("Reader migration target reconciliation failed")
    return {"migrationKey": migration_key, "dryRun": dry_run, **totals,
            "digestSha256": digest, "sourceHighWater": high_waters}


def validate_page(items: object, high_water: dict) -> None:
    """Validate bounded source page and composite snapshot cursor."""

    if not isinstance(items, list) or len(items) > PAGE_SIZE \
            or not isinstance(high_water["ownerId"], int) \
            or isinstance(high_water["ownerId"], bool) or high_water["ownerId"] < 0 \
            or not isinstance(high_water["key"], str) or len(high_water["key"]) > 1000:
        raise RuntimeError("Legacy Reader migration page is invalid")


def validate_high_waters(value: dict | None) -> dict:
    """Validate an optional caller-provided frozen cursor map."""

    if value is None:
        return {}
    if not isinstance(value, dict) or set(value) != set(ENTITY_TYPES):
        raise ValueError("Reader source high water is invalid")
    result = {}
    for entity_type, cursor in value.items():
        if not isinstance(cursor, dict) or set(cursor) != {"ownerId", "key"}:
            raise ValueError("Reader source high water is invalid")
        validate_page([], cursor)
        result[entity_type] = dict(cursor)
    return result


def item_identity(item: object) -> tuple[str, int, str, str]:
    """Build one target-compatible payload hash and collection identity."""

    required = {"entityType", "ownerId", "legacyKey", "bookId", "payload",
                "deleted", "revision", "serverUpdatedAt"}
    if not isinstance(item, dict) or set(item) != required or not isinstance(item["payload"], dict):
        raise RuntimeError("Legacy Reader migration item is invalid")
    normalized = dict(item["payload"])
    normalized.update({"legacyKey": item["legacyKey"], "legacyBookId": item["bookId"],
                       "deleted": item["deleted"], "revision": item["revision"],
                       "serverUpdatedAt": item["serverUpdatedAt"]})
    payload = json.dumps(normalized, separators=(",", ":"), ensure_ascii=False)
    raw = (f'{item["entityType"].upper()}\n{item["ownerId"]}\n'
           f'{item["legacyKey"]}\n{payload}').encode("utf-8")
    return (item["entityType"].upper(), int(item["ownerId"]), str(item["legacyKey"]),
            hashlib.sha256(raw).hexdigest())


def collection_digest(identities: list[tuple[str, int, str, str]]) -> str:
    """Hash payload hashes in the target database ordering."""

    digest = hashlib.sha256()
    for _entity_type, _owner_id, _legacy_key, payload_sha256 in sorted(identities):
        encoded = payload_sha256.encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)
    return digest.hexdigest()


def verify_batch(result: dict, size: int) -> None:
    """Require closing bounded batch counts and a valid digest."""

    values = [result.get(name) for name in ("accepted", "skipped", "rejected")]
    if any(not isinstance(value, int) or isinstance(value, bool) or value < 0 for value in values) \
            or sum(values) != size or DIGEST.fullmatch(str(result.get("digestSha256", ""))) is None:
        raise RuntimeError("Reader migration batch evidence is invalid")


def write_result(result: dict) -> None:
    """Atomically write a non-sensitive migration report."""

    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run one explicit Reader state migration task instance."""

    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("MYTOOLS_INTERNAL_URL", "http://127.0.0.1:23110"),
                    os.getenv("READER_MIGRATION_INTERNAL_TOKEN", ""),
                    os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                    os.getenv("READER_INTERNAL_TOKEN", ""))
    write_result(execute(client, str(parameters["migrationKey"]),
                         bool(parameters["dryRun"]), parameters.get("sourceHighWater")))


if __name__ == "__main__":
    main()
