#!/usr/bin/env python3
"""Migrate one frozen MsgService outbound archive without redelivery."""

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
    """Read frozen outbound snapshots and call protected Messaging APIs."""

    def __init__(self, legacy_url: str, legacy_token: str, messaging_url: str,
                 messaging_token: str, opener=urlopen):
        if not legacy_token or not messaging_token:
            raise ValueError("Outbound history migration tokens are missing")
        self.legacy_url = legacy_url.rstrip("/")
        self.legacy_token = legacy_token
        self.messaging_url = messaging_url.rstrip("/")
        self.messaging_token = messaging_token
        self.opener = opener

    def page(self, after_id: str | None, high_water: str | None) -> dict:
        """Read one page constrained by an opaque frozen high water."""

        query = {"limit": PAGE_SIZE}
        if after_id:
            query["afterId"] = after_id
        if high_water:
            query["snapshotHighWater"] = high_water
        return self._request(self.legacy_url + "/internal/v1/migration/outbound-messages?"
                             + urlencode(query), "GET", None, self.legacy_token)

    def import_batch(self, migration_key: str, dry_run: bool, items: list[dict]) -> dict:
        """Validate or archive one bounded target batch."""

        return self._request(self.messaging_url
                             + "/internal/v1/migrations/legacy-outbound/batches",
                             "POST", {"migrationKey": migration_key, "dryRun": dry_run,
                                      "items": items}, self.messaging_token)

    def evidence(self, migration_key: str) -> dict:
        """Read independently recomputed committed target evidence."""

        return self._request(self.messaging_url
                             + "/internal/v1/migrations/legacy-outbound/"
                             + migration_key + "/reconciliation",
                             "GET", None, self.messaging_token)

    def _request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        data = None if payload is None else json.dumps(
            payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            body = response.read()
        if len(body) > 16 * 1024 * 1024:
            raise RuntimeError("Outbound history migration response is too large")
        value = json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise RuntimeError("Outbound history migration endpoint returned invalid data")
        return value


def execute(client: Client, migration_key: str, dry_run: bool) -> dict:
    """Migrate all frozen pages and reconcile source and committed target evidence."""

    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Outbound history migration key is invalid")
    totals = {"exported": 0, "accepted": 0, "skipped": 0, "rejected": 0}
    batch_collection = hashlib.sha256()
    after_id = high_water = source_count = source_digest = None
    while True:
        page = client.page(after_id, high_water)
        items = page.get("items")
        if not isinstance(items, list) or len(items) > PAGE_SIZE:
            raise RuntimeError("Legacy outbound history page is invalid")
        current_high_water = str(page.get("snapshotHighWater") or "")
        current_count = page.get("itemCount")
        current_digest = str(page.get("collectionSha256") or "")
        if not current_high_water or len(current_high_water) > 255 \
                or not isinstance(current_count, int) or isinstance(current_count, bool) \
                or current_count < 0 or DIGEST.fullmatch(current_digest) is None:
            raise RuntimeError("Legacy outbound snapshot evidence is invalid")
        high_water = current_high_water if high_water is None else high_water
        source_count = current_count if source_count is None else source_count
        source_digest = current_digest if source_digest is None else source_digest
        if (current_high_water, current_count, current_digest) != \
                (high_water, source_count, source_digest):
            raise RuntimeError("Legacy outbound snapshot changed during migration")
        if items:
            result = client.import_batch(migration_key, dry_run, items)
            counts = [read_count(result, name) for name in ("accepted", "skipped", "rejected")]
            batch_digest = str(result.get("digestSha256") or "")
            if result.get("dryRun") is not dry_run or sum(counts) != len(items) \
                    or DIGEST.fullmatch(batch_digest) is None:
                raise RuntimeError("Outbound history migration batch evidence is invalid")
            totals["exported"] += len(items)
            for name, value in zip(("accepted", "skipped", "rejected"), counts, strict=True):
                totals[name] += value
            batch_collection.update(bytes.fromhex(batch_digest))
        next_after_id = page.get("nextAfterId")
        if next_after_id is None:
            break
        next_after_id = str(next_after_id)
        if not next_after_id or next_after_id == after_id:
            raise RuntimeError("Legacy outbound history cursor did not advance")
        after_id = next_after_id
    if totals["exported"] != source_count:
        raise RuntimeError("Legacy outbound snapshot count does not close")
    if not dry_run:
        target = client.evidence(migration_key)
        if target.get("migrationKey") != migration_key \
                or target.get("itemCount") != source_count \
                or target.get("collectionSha256") != source_digest:
            raise RuntimeError("Outbound history target reconciliation failed")
    return {"migrationKey": migration_key, "dryRun": dry_run, **totals,
            "digestSha256": batch_collection.hexdigest(), "sourceItemCount": source_count,
            "sourceDigestSha256": source_digest, "sourceHighWater": high_water}


def read_count(result: dict, name: str) -> int:
    """Read one non-negative batch count."""

    value = result.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise RuntimeError("Outbound history migration count is invalid")
    return value


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
    """Run one explicit outbound history migration task."""

    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("MSGSERVICE_MIGRATION_URL", "http://127.0.0.1:23320"),
                    os.getenv("MSGSERVICE_MIGRATION_TOKEN", ""),
                    os.getenv("MESSAGING_SERVICE_URL", "http://127.0.0.1:23250"),
                    os.getenv("MESSAGING_INTERNAL_TOKEN", ""))
    write_result(execute(client, str(parameters["migrationKey"]),
                         bool(parameters["dryRun"])))


if __name__ == "__main__":
    main()
