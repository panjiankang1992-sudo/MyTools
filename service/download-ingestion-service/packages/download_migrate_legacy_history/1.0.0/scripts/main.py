#!/usr/bin/env python3
"""分页迁移已封存的 DownloadBot 历史快照。"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import UUID

PAGE_SIZE = 200
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
DIGEST = re.compile(r"^[a-f0-9]{64}$")


class Client:
    """使用隔离令牌读取适配器并写入下载接入服务。"""

    def __init__(self, adapter_url: str, adapter_token: str, ingestion_url: str,
                 ingestion_token: str, opener=urlopen):
        if not adapter_token or not ingestion_token:
            raise ValueError("download history migration tokens are missing")
        self.adapter_url = adapter_url.rstrip("/")
        self.adapter_token = adapter_token
        self.ingestion_url = ingestion_url.rstrip("/")
        self.ingestion_token = ingestion_token
        self.opener = opener

    def page(self, snapshot_id: str, after_id: str | None) -> dict:
        """读取一个不可变快照页。"""
        query = {"snapshotId": snapshot_id, "limit": PAGE_SIZE}
        if after_id:
            query["afterId"] = after_id
        return self._request(
            self.adapter_url + "/internal/v1/migration/downloadbot/snapshot-items?"
            + urlencode(query), "GET", None, self.adapter_token)

    def import_batch(self, migration_key: str, dry_run: bool, items: list[dict]) -> dict:
        """预检或写入一个有界历史批次。"""
        return self._request(
            self.ingestion_url + "/internal/v1/migrations/downloadbot-history/batches",
            "POST", {"migrationKey": migration_key, "sourceSystem": "DownloadBot",
                     "dryRun": dry_run, "items": items}, self.ingestion_token)

    def _request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            body = response.read()
        if len(body) > 16 * 1024 * 1024:
            raise RuntimeError("download history migration response is too large")
        value = json.loads(body.decode())
        if not isinstance(value, dict):
            raise RuntimeError("download history migration response is invalid")
        return value


def execute(client: Client, migration_key: str, snapshot_id: str, dry_run: bool) -> dict:
    """迁移全部页面并验证数量闭合和源集合摘要。"""
    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("migration key is invalid")
    UUID(snapshot_id)
    totals = {"exported": 0, "accepted": 0, "skipped": 0, "rejected": 0}
    source_digest = hashlib.sha256()
    expected_count = None
    expected_digest = None
    after_id = None
    while True:
        page = client.page(snapshot_id, after_id)
        if str(page.get("snapshotId")) != snapshot_id:
            raise RuntimeError("snapshot identity changed during migration")
        items = page.get("items")
        if not isinstance(items, list) or len(items) > PAGE_SIZE:
            raise RuntimeError("snapshot page is invalid")
        page_count = page.get("itemCount")
        page_digest = str(page.get("collectionSha256") or "")
        if not isinstance(page_count, int) or isinstance(page_count, bool) or page_count < 0:
            raise RuntimeError("snapshot item count is invalid")
        if not DIGEST.fullmatch(page_digest):
            raise RuntimeError("snapshot digest is invalid")
        expected_count = page_count if expected_count is None else expected_count
        expected_digest = page_digest if expected_digest is None else expected_digest
        if page_count != expected_count or page_digest != expected_digest:
            raise RuntimeError("snapshot metadata changed during migration")
        for item in items:
            for value in (str(item.get("itemType")), str(item.get("legacyId")),
                          str(item.get("payloadSha256"))):
                encoded = value.encode()
                source_digest.update(len(encoded).to_bytes(4, "big"))
                source_digest.update(encoded)
        if items:
            result = client.import_batch(migration_key, dry_run, items)
            counts = [read_count(result, name) for name in ("accepted", "skipped", "rejected")]
            if result.get("dryRun") is not dry_run or sum(counts) != len(items):
                raise RuntimeError("download history import batch does not close")
            totals["exported"] += len(items)
            for name, value in zip(("accepted", "skipped", "rejected"), counts, strict=True):
                totals[name] += value
        next_after_id = page.get("nextAfterId")
        if next_after_id is None:
            break
        next_after_id = str(next_after_id)
        if not next_after_id or next_after_id == after_id:
            raise RuntimeError("snapshot cursor did not advance")
        after_id = next_after_id
    if totals["exported"] != expected_count or source_digest.hexdigest() != expected_digest:
        raise RuntimeError("snapshot collection evidence does not match")
    return {"migrationKey": migration_key, "sourceSnapshotId": snapshot_id,
            "dryRun": dry_run, **totals, "digestSha256": source_digest.hexdigest()}


def read_count(result: dict, name: str) -> int:
    """读取非负批次计数。"""
    value = result.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise RuntimeError("download history migration count is invalid")
    return value


def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行一次显式 DownloadBot 历史迁移任务。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("DOWNLOADBOT_ADAPTER_URL", "http://127.0.0.1:23221"),
                    os.getenv("DOWNLOADBOT_SNAPSHOT_EXPORT_TOKEN", ""),
                    os.getenv("DOWNLOAD_INGESTION_URL", "http://127.0.0.1:23220"),
                    os.getenv("DOWNLOAD_INGESTION_TOKEN", ""))
    write_result(execute(client, str(parameters["migrationKey"]),
                         str(parameters["sourceSnapshotId"]), parameters["dryRun"]))


if __name__ == "__main__":
    main()
