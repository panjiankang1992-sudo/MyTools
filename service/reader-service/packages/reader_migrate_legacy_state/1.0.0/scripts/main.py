#!/usr/bin/env python3
"""Migrate legacy shelf, progress, and marker state through protected APIs."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen

PAGE_SIZE = 200
ENTITY_TYPES = ("SHELF", "PROGRESS", "MARKER")


class Client:
    """Protected MyTools export and Reader import client."""

    def __init__(self, mytools_url: str, mytools_token: str, reader_url: str, reader_token: str,
                 opener=urlopen):
        if not mytools_token or not reader_token:
            raise ValueError("Reader migration tokens are missing")
        self.mytools_url = mytools_url.rstrip("/")
        self.mytools_token = mytools_token
        self.reader_url = reader_url.rstrip("/")
        self.reader_token = reader_token
        self.opener = opener

    def page(self, entity_type: str, owner_id: int, key: str) -> dict:
        """Read one stable legacy keyset page."""
        query = urlencode({"type": entity_type, "afterOwnerId": owner_id,
                           "afterKey": key, "limit": PAGE_SIZE})
        return self._request(self.mytools_url + "/internal/v1/migration/reader-state?" + query,
                             "GET", None, self.mytools_token)

    def import_batch(self, migration_key: str, dry_run: bool, items: list[dict]) -> dict:
        """Validate or idempotently import one bounded page."""
        return self._request(self.reader_url + "/api/internal/v1/migrations/legacy-reader/batches",
                             "POST", {"migrationKey": migration_key, "dryRun": dry_run,
                                      "items": items}, self.reader_token)

    def _request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            value = json.loads(response.read().decode("utf-8"))
        if not isinstance(value, dict):
            raise RuntimeError("Reader migration endpoint returned an invalid response")
        return value


def execute(client: Client, migration_key: str, dry_run: bool) -> dict:
    """Migrate all entity types in dependency order and produce a source digest."""
    totals = {"exported": 0, "accepted": 0, "skipped": 0, "rejected": 0}
    digest = hashlib.sha256()
    for entity_type in ENTITY_TYPES:
        owner_id = 0
        key = ""
        while True:
            page = client.page(entity_type, owner_id, key)
            items = page.get("items")
            if not isinstance(items, list):
                raise RuntimeError("Legacy Reader migration page is invalid")
            if items:
                result = client.import_batch(migration_key, dry_run, items)
                totals["exported"] += len(items)
                for name in ("accepted", "skipped", "rejected"):
                    totals[name] += int(result.get(name, 0))
                for item in items:
                    digest.update(json.dumps(item, sort_keys=True, separators=(",", ":")).encode())
                    digest.update(b"\n")
            next_owner = int(page.get("nextAfterOwnerId", owner_id))
            next_key = str(page.get("nextAfterKey", key))
            if items and (next_owner, next_key) <= (owner_id, key):
                raise RuntimeError("Legacy Reader migration cursor did not advance")
            owner_id, key = next_owner, next_key
            if page.get("complete") is True:
                break
    return {"dryRun": dry_run, **totals, "digestSha256": digest.hexdigest()}


def write_result(result: dict) -> None:
    """Atomically write the migration report."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run one manual Reader state migration task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("MYTOOLS_INTERNAL_URL", "http://127.0.0.1:23110"),
                    os.getenv("READER_MIGRATION_INTERNAL_TOKEN", ""),
                    os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                    os.getenv("READER_INTERNAL_TOKEN", ""))
    write_result(execute(client, str(parameters["migrationKey"]), bool(parameters["dryRun"])))


if __name__ == "__main__":
    main()
