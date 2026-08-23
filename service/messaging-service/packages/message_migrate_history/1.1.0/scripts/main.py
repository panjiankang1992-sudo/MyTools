#!/usr/bin/env python3
"""迁移冻结高水位内的标准化 MsgService 历史消息。"""

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
    """使用隔离凭据读取旧快照并写入 Messaging。"""

    def __init__(self, legacy_url: str, legacy_token: str, messaging_url: str,
                 messaging_token: str, opener=urlopen):
        if not legacy_token or not messaging_token:
            raise ValueError("Message history migration tokens are missing")
        self.legacy_url = legacy_url.rstrip("/")
        self.legacy_token = legacy_token
        self.messaging_url = messaging_url.rstrip("/")
        self.messaging_token = messaging_token
        self.opener = opener

    def page(self, after_id: str | None, high_water: str | None) -> dict:
        """读取冻结高水位内的一页。"""
        query = {"limit": PAGE_SIZE}
        if after_id:
            query["afterId"] = after_id
        if high_water:
            query["snapshotHighWater"] = high_water
        return self._request(self.legacy_url + "/internal/v1/migration/inbound-messages?"
                             + urlencode(query), "GET", None, self.legacy_token)

    def import_batch(self, migration_key: str, dry_run: bool, items: list[dict]) -> dict:
        """校验或写入一个有界批次。"""
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


def execute(client: Client, migration_key: str, dry_run: bool) -> dict:
    """迁移全部冻结页，并验证每页来源证据保持不变。"""
    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Message history migration key is invalid")
    totals = {"exported": 0, "accepted": 0, "skipped": 0, "rejected": 0}
    target_digest = hashlib.sha256()
    after_id = None
    high_water = None
    source_count = None
    source_digest = None
    while True:
        page = client.page(after_id, high_water)
        items = page.get("items")
        if not isinstance(items, list) or len(items) > PAGE_SIZE:
            raise RuntimeError("Legacy message history page is invalid")
        current_high_water = str(page.get("snapshotHighWater") or "")
        current_count = page.get("itemCount")
        current_digest = str(page.get("collectionSha256") or "")
        if not current_high_water or len(current_high_water) > 255 \
                or not isinstance(current_count, int) or isinstance(current_count, bool) \
                or current_count < 0 or DIGEST.fullmatch(current_digest) is None:
            raise RuntimeError("Legacy message snapshot evidence is invalid")
        high_water = current_high_water if high_water is None else high_water
        source_count = current_count if source_count is None else source_count
        source_digest = current_digest if source_digest is None else source_digest
        if (current_high_water, current_count, current_digest) != \
                (high_water, source_count, source_digest):
            raise RuntimeError("Legacy message snapshot changed during migration")
        if items:
            result = client.import_batch(migration_key, dry_run, items)
            counts = [read_count(result, name) for name in ("accepted", "skipped", "rejected")]
            if result.get("dryRun") is not dry_run or sum(counts) != len(items):
                raise RuntimeError("Message history migration counts do not close")
            batch_digest = str(result.get("digestSha256") or "")
            if DIGEST.fullmatch(batch_digest) is None:
                raise RuntimeError("Message history migration digest is invalid")
            totals["exported"] += len(items)
            for name, value in zip(("accepted", "skipped", "rejected"), counts, strict=True):
                totals[name] += value
            target_digest.update(bytes.fromhex(batch_digest))
        next_after_id = page.get("nextAfterId")
        if next_after_id is None:
            break
        next_after_id = str(next_after_id)
        if not next_after_id or next_after_id == after_id:
            raise RuntimeError("Legacy message history cursor did not advance")
        after_id = next_after_id
    if totals["exported"] != source_count:
        raise RuntimeError("Legacy message snapshot count does not close")
    return {"migrationKey": migration_key, "dryRun": dry_run, **totals,
            "digestSha256": target_digest.hexdigest(), "sourceItemCount": source_count,
            "sourceDigestSha256": source_digest, "sourceHighWater": high_water}


def read_count(result: dict, name: str) -> int:
    """读取非负批次计数。"""
    value = result.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise RuntimeError("Message history migration count is invalid")
    return value


def write_result(result: dict) -> None:
    """原子写入迁移报告。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行一次显式历史消息迁移。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("MSGSERVICE_MIGRATION_URL", "http://127.0.0.1:23320"),
                    os.getenv("MSGSERVICE_MIGRATION_TOKEN", ""),
                    os.getenv("MESSAGING_SERVICE_URL", "http://127.0.0.1:23250"),
                    os.getenv("MESSAGING_INTERNAL_TOKEN", ""))
    write_result(execute(client, str(parameters["migrationKey"]), bool(parameters["dryRun"])))


if __name__ == "__main__":
    main()
