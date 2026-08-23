#!/usr/bin/env python3
"""从 DownloadBot 旧库捕获一个一致性、可审计的迁移快照。"""

from __future__ import annotations

from datetime import UTC, datetime
import hashlib
import json
import os
from pathlib import Path
import tempfile
from uuid import UUID, uuid4

import pymysql
from pymysql.cursors import DictCursor

PAGE_SIZE = 500


def canonical(value: dict) -> str:
    """返回规范化 JSON 字符串。"""
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), default=str)


def normalize_asset(row: dict) -> tuple[str, str, dict]:
    """标准化旧资产且不导出物理路径。"""
    legacy_id = str(row["id"])
    sha256 = str(row.get("sha256") or "").lower()
    if len(sha256) != 64 or any(value not in "0123456789abcdef" for value in sha256):
        raise ValueError("INVALID_SHA256")
    size = int(row.get("size") or 0)
    if size < 0:
        raise ValueError("INVALID_SIZE")
    tags = row.get("tags_json")
    try:
        tags = tags if isinstance(tags, (dict, list)) else json.loads(str(tags or "{}"))
    except json.JSONDecodeError:
        tags = {}
    return legacy_id, f"asset:{sha256}", {
        "legacyAssetId": legacy_id, "contentSha256": sha256,
        "fileName": str(row.get("file_name") or ""),
        "mimeType": str(row.get("mime") or "application/octet-stream"),
        "sizeBytes": size, "category": str(row.get("category") or "OTHER"),
        "tagStatus": str(row.get("tag_status") or ""), "tags": tags,
        "createdAt": row.get("created_at")}


def normalize_link_asset(row: dict) -> tuple[str, str, dict]:
    """标准化链接与资产关系。"""
    legacy_id = str(row["id"])
    sha256 = str(row.get("sha256") or "").lower()
    if len(sha256) != 64 or any(value not in "0123456789abcdef" for value in sha256):
        raise ValueError("INVALID_SHA256")
    link_id, asset_id = str(row.get("link_job_id") or ""), str(row.get("asset_id") or "")
    if not link_id or not asset_id:
        raise ValueError("MISSING_RELATION")
    return legacy_id, f"link-asset:{link_id}:{asset_id}", {
        "legacyLinkJobId": link_id, "legacyAssetId": asset_id,
        "contentSha256": sha256, "sourceKey": str(row.get("source_key") or legacy_id),
        "createdAt": row.get("created_at")}


def normalize_event_asset(row: dict) -> tuple[str, str, dict]:
    """标准化普通消息事件与资产关系且隐藏消息路由。"""
    legacy_id = str(row.get("id") or "")
    asset_id = str(row.get("asset_id") or "")
    digest = str(row.get("sha256") or "").lower()
    platform = str(row.get("platform") or "").lower()
    account_id = str(row.get("bot_account_id") or "")
    event_id = str(row.get("event_id") or "")
    source_index = int(row.get("source_index") or 0)
    if not legacy_id or not asset_id:
        raise ValueError("MISSING_RELATION")
    if len(digest) != 64 or any(value not in "0123456789abcdef" for value in digest):
        raise ValueError("INVALID_SHA256")
    if (not platform or len(platform) > 32
            or any(value not in "abcdefghijklmnopqrstuvwxyz0123456789_-" for value in platform)
            or not account_id or not event_id):
        raise ValueError("MISSING_EVENT_IDENTITY")
    if source_index < 0:
        raise ValueError("INVALID_SOURCE_INDEX")
    event_key = hashlib.sha256(canonical({
        "platform": platform, "botAccountId": account_id, "eventId": event_id,
    }).encode()).hexdigest()
    return legacy_id, f"event-asset:{event_key}:{source_index}", {
        "legacyAssetSourceId": legacy_id, "legacyAssetId": asset_id,
        "eventKeySha256": event_key, "sourceSystem": f"DOWNLOADBOT_{platform.upper()}",
        "sourceIndex": source_index, "contentSha256": digest,
        "receivedAt": row.get("received_at")}


def normalize_link_job(row: dict) -> tuple[str, str, dict]:
    """标准化链接作业且不导出 URL 和消息路由。"""
    legacy_id = str(row["id"])
    digest = str(row.get("uri_sha256") or "").lower()
    if len(digest) != 64:
        raise ValueError("INVALID_URI_DIGEST")
    return legacy_id, f"link:{digest}", {
        "legacyJobId": legacy_id, "uriSha256": digest,
        "requestKind": str(row.get("link_kind") or "UNKNOWN"),
        "strategy": str(row.get("strategy") or "UNKNOWN"),
        "sourceType": str(row.get("source_type") or "LEGACY"),
        "sourceKey": str(row.get("source_key") or legacy_id),
        "status": str(row.get("status") or "UNKNOWN"),
        "expectedFiles": int(row.get("expected_files") or 0),
        "createdAt": row.get("created_at"), "completedAt": row.get("completed_at")}


def capture(source, target, snapshot_id: str, source_schema: str) -> dict:
    """在旧库一致性视图内分页捕获并原子封存新快照。"""
    UUID(snapshot_id)
    with target.cursor() as cursor:
        cursor.execute("SELECT * FROM legacy_snapshot WHERE id=%s", (snapshot_id,))
        existing = cursor.fetchone()
    if existing is not None:
        if existing["status"] != "SEALED":
            raise RuntimeError("downloadbot snapshot is not sealed")
        return report(existing)
    with source.cursor() as cursor:
        cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
    high_water = {}
    with source.cursor() as cursor:
        for table, key in (("assets", "assets"), ("asset_sources", "eventAssets"),
                           ("link_asset_sources", "linkAssets"),
                           ("link_jobs", "linkJobs")):
            cursor.execute(f"SELECT COALESCE(MAX(id),0) AS value FROM {table}")
            high_water[key] = int(cursor.fetchone()["value"])
    started = datetime.now(UTC)
    with target.cursor() as cursor:
        cursor.execute(
            "INSERT INTO legacy_snapshot "
            "(id,status,source_schema,source_version,high_water_json,item_count,rejection_count,"
            "collection_sha256,started_at,sealed_at) VALUES (%s,'CAPTURING',%s,'mysql-v2',%s,0,0,NULL,%s,NULL)",
            (snapshot_id, source_schema, json.dumps(high_water, separators=(",", ":")), started))
    captured = rejected = 0
    specs = [
        ("ASSET", "assets", high_water["assets"],
         "SELECT * FROM assets WHERE id>%s AND id<=%s ORDER BY id LIMIT %s", normalize_asset),
        ("EVENT_ASSET", "asset_sources", high_water["eventAssets"],
         "SELECT source.*,asset.sha256,event.platform,event.bot_account_id,event.event_id,"
         "event.received_at FROM asset_sources source "
         "JOIN assets asset ON asset.id=source.asset_id "
         "JOIN ingress_events event ON event.id=source.event_row_id "
         "WHERE source.id>%s AND source.id<=%s ORDER BY source.id LIMIT %s",
         normalize_event_asset),
        ("LINK_ASSET", "link_asset_sources", high_water["linkAssets"],
         "SELECT source.*,asset.sha256 FROM link_asset_sources source JOIN assets asset "
         "ON asset.id=source.asset_id WHERE source.id>%s AND source.id<=%s ORDER BY source.id LIMIT %s",
         normalize_link_asset),
        ("LINK_JOB", "link_jobs", high_water["linkJobs"],
         "SELECT * FROM link_jobs WHERE id>%s AND id<=%s ORDER BY id LIMIT %s", normalize_link_job),
    ]
    for item_type, _table, maximum, query, normalizer in specs:
        after = 0
        while after < maximum:
            with source.cursor() as cursor:
                cursor.execute(query, (after, maximum, PAGE_SIZE))
                rows = list(cursor.fetchall())
            if not rows:
                break
            with target.cursor() as cursor:
                for row in rows:
                    after = int(row["id"])
                    try:
                        legacy_id, source_key, payload = normalizer(row)
                        payload_json = canonical(payload)
                        payload_digest = hashlib.sha256(payload_json.encode()).hexdigest()
                        cursor.execute(
                            "INSERT INTO legacy_snapshot_item "
                            "(id,snapshot_id,item_type,legacy_id,source_key,payload_json,payload_sha256,created_at) "
                            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
                            (str(uuid4()), snapshot_id, item_type, legacy_id, source_key,
                             payload_json, payload_digest, datetime.now(UTC)))
                        captured += 1
                    except ValueError as exception:
                        cursor.execute(
                            "INSERT INTO legacy_snapshot_rejection "
                            "(id,snapshot_id,item_type,legacy_id,reason_code,detail,created_at) "
                            "VALUES (%s,%s,%s,%s,%s,%s,%s)",
                            (str(uuid4()), snapshot_id, item_type, str(row.get("id") or ""),
                             str(exception), "legacy row failed normalization", datetime.now(UTC)))
                        rejected += 1
    # 集合摘要使用与分页导出完全相同的词法顺序，避免数字主键位数改变摘要。
    digest = hashlib.sha256()
    with target.cursor() as cursor:
        cursor.execute(
            "SELECT item_type,legacy_id,payload_sha256 FROM legacy_snapshot_item "
            "WHERE snapshot_id=%s ORDER BY item_type,legacy_id", (snapshot_id,))
        for row in cursor.fetchall():
            for value in (row["item_type"], row["legacy_id"], row["payload_sha256"]):
                encoded = str(value).encode()
                digest.update(len(encoded).to_bytes(4, "big"))
                digest.update(encoded)
    sealed = datetime.now(UTC)
    with target.cursor() as cursor:
        cursor.execute(
            "UPDATE legacy_snapshot SET status='SEALED',item_count=%s,rejection_count=%s,"
            "collection_sha256=%s,sealed_at=%s WHERE id=%s AND status='CAPTURING'",
            (captured, rejected, digest.hexdigest(), sealed, snapshot_id))
        if cursor.rowcount != 1:
            raise RuntimeError("downloadbot snapshot seal transition failed")
    target.commit()
    source.rollback()
    return {"snapshotId": snapshot_id, "status": "SEALED", "itemCount": captured,
            "rejectionCount": rejected, "collectionSha256": digest.hexdigest()}


def report(row: dict) -> dict:
    """返回已存在封存快照的任务结果。"""
    return {"snapshotId": row["id"], "status": row["status"],
            "itemCount": int(row["item_count"]), "rejectionCount": int(row["rejection_count"]),
            "collectionSha256": row["collection_sha256"]}


def connect(prefix: str, default_database: str):
    """创建使用隔离账号的 MySQL 连接。"""
    return pymysql.connect(host=os.getenv(prefix + "_HOST", "127.0.0.1"),
                           port=int(os.getenv(prefix + "_PORT", "3306")),
                           user=os.environ[prefix + "_USER"], password=os.environ[prefix + "_PASSWORD"],
                           database=os.getenv(prefix + "_NAME", default_database), charset="utf8mb4",
                           cursorclass=DictCursor, autocommit=False)


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
    """执行一个由调度器显式创建的快照任务。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    source = connect("DOWNLOADBOT_LEGACY_DB", "downloadbot")
    target = connect("DOWNLOADBOT_ADAPTER_DB", "mytools_downloadbot_adapter")
    try:
        write_result(capture(source, target, str(parameters["snapshotId"]),
                             os.getenv("DOWNLOADBOT_LEGACY_DB_NAME", "downloadbot")))
    except Exception:
        target.rollback()
        source.rollback()
        raise
    finally:
        source.close()
        target.close()


if __name__ == "__main__":
    main()
