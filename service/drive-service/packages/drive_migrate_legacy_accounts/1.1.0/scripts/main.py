#!/usr/bin/env python3
"""Migrate a frozen legacy Drive account set with target reconciliation."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen


PAGE_SIZE = 100
SOURCES = ("DRIVE", "WEBDAV")
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
DIGEST = re.compile(r"^[a-f0-9]{64}$")


class Client:
    """Read the frozen legacy export and call bounded Drive migration APIs."""

    def __init__(self, legacy_url: str, legacy_token: str, drive_url: str,
                 drive_token: str, opener=urlopen):
        if not legacy_token or not drive_token:
            raise ValueError("Drive migration tokens are missing")
        self.legacy_url = legacy_url.rstrip("/")
        self.legacy_token = legacy_token
        self.drive_url = drive_url.rstrip("/")
        self.drive_token = drive_token
        self.opener = opener

    def page(self, source: str, after_id: int, high_water: int | None) -> dict:
        """Read one page constrained by a stable source high water."""

        query = {"source": source, "afterId": after_id, "limit": PAGE_SIZE}
        if high_water is not None:
            query["snapshotHighWater"] = high_water
        return self._request(self.legacy_url + "/internal/v1/migration/drive-accounts?"
                             + urlencode(query), "GET", None, self.legacy_token)

    def import_batch(self, migration_key: str, dry_run: bool, items: list[dict]) -> dict:
        """Validate or import one bounded target batch."""

        return self._request(self.drive_url
                             + "/internal/v1/drive/migrations/legacy-accounts/batches",
                             "POST", {"migrationKey": migration_key, "dryRun": dry_run,
                                      "items": items}, self.drive_token)

    def evidence(self, migration_key: str) -> dict:
        """Read the committed target collection evidence."""

        query = urlencode({"migrationKey": migration_key})
        return self._request(self.drive_url
                             + "/internal/v1/drive/migrations/legacy-accounts/evidence?" + query,
                             "GET", None, self.drive_token)

    def _request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            body = response.read()
        if len(body) > 4 * 1024 * 1024:
            raise RuntimeError("Drive migration response is too large")
        value = json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise RuntimeError("Drive migration endpoint returned invalid data")
        return value


def execute(client: Client, migration_key: str, dry_run: bool,
            source_high_water: dict[str, int] | None = None) -> dict:
    """Migrate two frozen sources and prove source and target collections match."""

    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Drive migration key is invalid")
    high_waters = validate_high_waters(source_high_water)
    collection = hashlib.sha256()
    totals = {"exported": 0, "accepted": 0, "skipped": 0, "rejected": 0}
    for source in SOURCES:
        after_id = 0
        while True:
            page = client.page(source, after_id, high_waters.get(source))
            accounts = page.get("accounts")
            current_high_water = page.get("snapshotHighWater")
            if not isinstance(accounts, list) or len(accounts) > PAGE_SIZE \
                    or not isinstance(current_high_water, int) \
                    or isinstance(current_high_water, bool) or current_high_water < 0:
                raise RuntimeError("Legacy Drive account page is invalid")
            if source in high_waters and current_high_water != high_waters[source]:
                raise RuntimeError("Legacy Drive account high water changed")
            high_waters[source] = current_high_water
            items = [migration_item(source, account) for account in accounts]
            for item in items:
                update_digest(collection, item["sourceSystem"], item["legacyAccountId"],
                              account_digest(item["account"]))
            if items:
                result = client.import_batch(migration_key, dry_run, items)
                verify_batch(result, migration_key, dry_run, len(items))
                for name in totals:
                    totals[name] += int(result[name])
            next_after = page.get("nextAfterId")
            if not isinstance(next_after, int) or isinstance(next_after, bool) \
                    or next_after < after_id or (accounts and next_after <= after_id):
                raise RuntimeError("Legacy Drive account cursor did not advance")
            after_id = next_after
            if page.get("complete") is True:
                break
    digest = collection.hexdigest()
    if totals["exported"] != sum(totals[name] for name in ("accepted", "skipped", "rejected")):
        raise RuntimeError("Drive migration totals do not close")
    if not dry_run:
        evidence = client.evidence(migration_key)
        if evidence.get("migrationKey") != migration_key \
                or evidence.get("itemCount") != totals["exported"] \
                or evidence.get("digestSha256") != digest:
            raise RuntimeError("Drive migration target reconciliation failed")
    return {"migrationKey": migration_key, "dryRun": dry_run, **totals,
            "digestSha256": digest, "sourceHighWater": high_waters}


def validate_high_waters(value: dict[str, int] | None) -> dict[str, int]:
    """Validate optional caller-provided frozen source cursors."""

    if value is None:
        return {}
    if not isinstance(value, dict) or set(value) != set(SOURCES):
        raise ValueError("Drive migration high water is invalid")
    if any(not isinstance(item, int) or isinstance(item, bool) or item < 0 for item in value.values()):
        raise ValueError("Drive migration high water is invalid")
    return dict(value)


def migration_item(source: str, account: object) -> dict:
    """Convert and validate one sanitized legacy export row."""

    required = {"legacyId", "ownerId", "externalAccountId", "displayName", "providerType",
                "providerSecretRef", "remoteKey", "readOnly", "enabled"}
    if not isinstance(account, dict) or set(account) != required:
        raise RuntimeError("Legacy Drive account contains invalid fields")
    legacy_id = account["legacyId"]
    owner_id = account["ownerId"]
    if not isinstance(legacy_id, int) or isinstance(legacy_id, bool) or legacy_id <= 0 \
            or not isinstance(owner_id, int) or isinstance(owner_id, bool) or owner_id <= 0:
        raise RuntimeError("Legacy Drive account identity is invalid")
    return {"sourceSystem": source, "legacyAccountId": legacy_id,
            "account": {key: account[key] for key in required if key not in {"legacyId", "ownerId"}}
                       | {"ownerId": owner_id}}


def account_digest(account: dict) -> str:
    """Calculate the target-compatible stable account payload digest."""

    digest = hashlib.sha256()
    update_digest(digest, account["ownerId"], account["externalAccountId"], account["displayName"],
                  account["providerType"], account["providerSecretRef"], account["remoteKey"],
                  str(account["readOnly"]).lower(), str(account["enabled"]).lower())
    return digest.hexdigest()


def update_digest(digest, *values: object) -> None:
    """Update a SHA-256 value with the shared length-prefixed protocol."""

    for value in values:
        encoded = str(value).encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)


def verify_batch(result: dict, migration_key: str, dry_run: bool, size: int) -> None:
    """Require a closing, authenticated-looking target batch report."""

    if result.get("migrationKey") != migration_key or result.get("dryRun") is not dry_run \
            or result.get("exported") != size or not DIGEST.fullmatch(str(result.get("digestSha256", ""))):
        raise RuntimeError("Drive migration batch evidence is invalid")
    values = [result.get(name) for name in ("accepted", "skipped", "rejected")]
    if any(not isinstance(value, int) or isinstance(value, bool) or value < 0 for value in values) \
            or sum(values) != size:
        raise RuntimeError("Drive migration batch counts do not close")


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
    """Run one explicit Drive account migration task instance."""

    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("MYTOOLS_INTERNAL_URL", "http://127.0.0.1:23110"),
                    os.getenv("DRIVE_MIGRATION_INTERNAL_TOKEN", ""),
                    os.getenv("DRIVE_SERVICE_URL", "http://127.0.0.1:23280"),
                    os.getenv("DRIVE_INTERNAL_TOKEN", ""))
    write_result(execute(client, str(parameters["migrationKey"]), bool(parameters["dryRun"]),
                         parameters.get("sourceHighWater")))


if __name__ == "__main__":
    main()
