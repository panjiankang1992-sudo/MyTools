#!/usr/bin/env python3
"""Migrate sanitized historical inbound messages through protected APIs."""

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
    """Read legacy adapter pages and submit bounded Messaging import batches."""

    def __init__(self, legacy_url: str, legacy_token: str, messaging_url: str,
                 messaging_token: str, opener=urlopen):
        """Create a client with isolated read and write credentials."""
        if not legacy_token or not messaging_token:
            raise ValueError("Message history migration tokens are missing")
        self.legacy_url = legacy_url.rstrip("/")
        self.legacy_token = legacy_token
        self.messaging_url = messaging_url.rstrip("/")
        self.messaging_token = messaging_token
        self.opener = opener

    def page(self, after_id: str | None) -> dict:
        """Read one stable sanitized legacy message page."""
        query = {"limit": PAGE_SIZE}
        if after_id:
            query["afterId"] = after_id
        return self._request(self.legacy_url + "/internal/v1/migration/inbound-messages?"
                             + urlencode(query), "GET", None, self.legacy_token)

    def import_batch(self, migration_key: str, dry_run: bool, items: list[dict]) -> dict:
        """Validate or import one bounded page into Messaging."""
        return self._request(self.messaging_url + "/internal/v1/migrations/legacy-inbound/batches",
                             "POST", {"migrationKey": migration_key, "dryRun": dry_run,
                                      "items": items}, self.messaging_token)

    def _request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            body = response.read()
        if len(body) > 16 * 1024 * 1024:
            raise RuntimeError("Message history migration response is too large")
        value = json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise RuntimeError("Message history migration endpoint returned invalid data")
        return value


def execute(client: Client, migration_key: str, dry_run: bool,
            start_after_id: str | None = None) -> dict:
    """Migrate all pages and aggregate deterministic batch evidence."""
    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Message history migration key is invalid")
    totals = {"exported": 0, "accepted": 0, "skipped": 0, "rejected": 0}
    digest = hashlib.sha256()
    after_id = start_after_id
    last_after_id = start_after_id
    while True:
        page = client.page(after_id)
        items = page.get("items")
        if not isinstance(items, list) or len(items) > PAGE_SIZE:
            raise RuntimeError("Legacy message history page is invalid")
        if items:
            result = client.import_batch(migration_key, dry_run, items)
            if result.get("dryRun") is not dry_run:
                raise RuntimeError("Message history migration mode does not match")
            counts = [count(result, name) for name in ("accepted", "skipped", "rejected")]
            if sum(counts) != len(items):
                raise RuntimeError("Message history migration counts do not close")
            batch_digest = str(result.get("digestSha256", ""))
            if not DIGEST.fullmatch(batch_digest):
                raise RuntimeError("Message history migration digest is invalid")
            totals["exported"] += len(items)
            for name, value in zip(("accepted", "skipped", "rejected"), counts, strict=True):
                totals[name] += value
            digest.update(bytes.fromhex(batch_digest))
            last = items[-1]
            if isinstance(last, dict) and "legacyMessageId" in last:
                last_after_id = str(last["legacyMessageId"])[:255]
        next_after_id = page.get("nextAfterId")
        if next_after_id is None:
            break
        next_after_id = str(next_after_id)
        if not next_after_id or next_after_id == after_id:
            raise RuntimeError("Legacy message history cursor did not advance")
        after_id = next_after_id
        last_after_id = next_after_id
    return {"migrationKey": migration_key, "dryRun": dry_run, **totals,
            "digestSha256": digest.hexdigest(), "lastAfterId": last_after_id}


def count(result: dict, name: str) -> int:
    """Read one non-negative integer count without accepting booleans."""
    value = result.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise RuntimeError("Message history migration count is invalid")
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
    """Run one explicit historical inbound migration task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("MSGSERVICE_MIGRATION_URL", "http://127.0.0.1:23320"),
                    os.getenv("MSGSERVICE_MIGRATION_TOKEN", ""),
                    os.getenv("MESSAGING_SERVICE_URL", "http://127.0.0.1:23250"),
                    os.getenv("MESSAGING_INTERNAL_TOKEN", ""))
    start_after_id = parameters.get("afterId")
    write_result(execute(client, str(parameters["migrationKey"]), bool(parameters["dryRun"]),
                         None if start_after_id is None else str(start_after_id)))


if __name__ == "__main__":
    main()
