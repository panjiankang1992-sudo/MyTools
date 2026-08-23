#!/usr/bin/env python3
"""Materialize MyTools local_file from one consistent read-only transaction."""

from __future__ import annotations

from datetime import UTC, datetime
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote
from uuid import uuid4

import pymysql
from pymysql.cursors import DictCursor

SNAPSHOT_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
SHA256 = re.compile(r"^[a-fA-F0-9]{64}$")
PAGE_SIZE = 500


def normalize(row: dict, owner_id: int) -> dict:
    """Map one valid local_file row to the target migration contract."""
    legacy_id = int(row["id"])
    content_sha256 = str(row.get("file_hash") or "")
    if not SHA256.fullmatch(content_sha256):
        raise ValueError("HASH_MISSING_OR_INVALID")
    size = row.get("file_size")
    if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
        raise ValueError("SIZE_INVALID")
    file_path = str(row.get("file_path") or "")
    if not file_path.startswith("/") or "\x00" in file_path:
        raise ValueError("PATH_INVALID")
    mime_type = str(row.get("mime_type") or "application/octet-stream")
    if not mime_type or len(mime_type) > 255:
        raise ValueError("MIME_INVALID")
    business_id = f"local_file:{legacy_id}"
    location = {"idempotencyKey": f"legacy-local-location:{legacy_id}",
                "providerType": "LEGACY_LOCAL", "storageUri": "file://" + quote(file_path, safe="/"),
                "providerVersion": str(row.get("update_time") or "legacy")[:255]}
    return {"sourceSystem": "MyTools", "legacyAssetId": str(legacy_id), "asset": {
            "ownerId": owner_id, "idempotencyKey": f"legacy-local-asset:{legacy_id}",
            "sourceType": "LEGACY_ASSET", "sourceBusinessId": business_id,
            "contentSha256": content_sha256.lower(), "sizeBytes": size,
            "mimeType": mime_type, "location": location}}


def payload_digest(payload: dict) -> str:
    """Return a canonical snapshot item digest."""
    data = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(data).hexdigest()


def capture(source, target, snapshot_id: str, owner_id: int) -> dict:
    """Capture and atomically seal one consistent snapshot."""
    if not SNAPSHOT_ID.fullmatch(snapshot_id):
        raise ValueError("Legacy asset snapshot id is invalid")
    if owner_id < 0:
        raise ValueError("Legacy asset owner id is invalid")
    with target.cursor() as cursor:
        cursor.execute("SELECT * FROM legacy_asset_snapshot WHERE snapshot_id=%s", (snapshot_id,))
        existing = cursor.fetchone()
    if existing is not None:
        if existing["status"] != "SEALED":
            raise RuntimeError("Legacy asset snapshot is not sealed")
        if int(existing["owner_id"]) != owner_id:
            raise ValueError("Legacy asset snapshot owner conflicts")
        return report(existing)

    try:
        with source.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")
            cursor.execute("SELECT COALESCE(MAX(id),0) AS high_water_id FROM local_file")
            high_water_id = int(cursor.fetchone()["high_water_id"])
        now = datetime.now(UTC)
        with target.cursor() as cursor:
            cursor.execute(
                "INSERT INTO legacy_asset_snapshot "
                "(snapshot_id,source_system,owner_id,high_water_id,captured_count,rejected_count,digest_sha256,"
                "status,created_at,sealed_at) VALUES (%s,'MyTools',%s,%s,0,0,%s,'CAPTURING',%s,%s)",
                (snapshot_id, owner_id, high_water_id, "0" * 64, now, now))
        after_id = captured = rejected = 0
        digest = hashlib.sha256()
        while after_id < high_water_id:
            with source.cursor() as cursor:
                cursor.execute(
                    "SELECT id,file_path,file_size,mime_type,file_hash,update_time FROM local_file "
                    "WHERE deleted=0 AND id>%s AND id<=%s ORDER BY id LIMIT %s",
                    (after_id, high_water_id, PAGE_SIZE))
                rows = cursor.fetchall()
            if not rows:
                break
            with target.cursor() as cursor:
                for row in rows:
                    after_id = int(row["id"])
                    try:
                        payload = normalize(row, owner_id)
                        item_digest = payload_digest(payload)
                        cursor.execute(
                            "INSERT INTO legacy_asset_snapshot_item "
                            "(snapshot_id,legacy_asset_id,payload_sha256,payload_json) VALUES (%s,%s,%s,%s)",
                            (snapshot_id, str(after_id), item_digest,
                             json.dumps(payload, separators=(",", ":"), ensure_ascii=False)))
                        digest.update(bytes.fromhex(item_digest))
                        captured += 1
                    except ValueError as exception:
                        cursor.execute(
                            "INSERT INTO legacy_asset_snapshot_rejection "
                            "(id,snapshot_id,legacy_asset_id,reason_code,created_at) VALUES (%s,%s,%s,%s,%s)",
                            (str(uuid4()), snapshot_id, str(after_id), str(exception), now))
                        rejected += 1
        sealed_at = datetime.now(UTC)
        digest_sha256 = digest.hexdigest()
        with target.cursor() as cursor:
            cursor.execute(
                "UPDATE legacy_asset_snapshot SET captured_count=%s,rejected_count=%s,digest_sha256=%s,"
                "status='SEALED',sealed_at=%s WHERE snapshot_id=%s AND status='CAPTURING'",
                (captured, rejected, digest_sha256, sealed_at, snapshot_id))
        target.commit()
        source.rollback()
        return {"snapshotId": snapshot_id, "ownerId": owner_id, "highWaterId": high_water_id,
                "captured": captured, "rejected": rejected, "digestSha256": digest_sha256}
    except Exception:
        target.rollback()
        source.rollback()
        raise


def report(row: dict) -> dict:
    """Map one sealed snapshot row to the task result."""
    return {"snapshotId": row["snapshot_id"], "ownerId": int(row["owner_id"]),
            "highWaterId": int(row["high_water_id"]),
            "captured": int(row["captured_count"]), "rejected": int(row["rejected_count"]),
            "digestSha256": row["digest_sha256"]}


def connect(prefix: str, default_database: str):
    """Create one explicit migration database connection."""
    return pymysql.connect(host=os.getenv(prefix + "_HOST", "127.0.0.1"),
                           port=int(os.getenv(prefix + "_PORT", "3306")),
                           user=os.environ[prefix + "_USER"], password=os.environ[prefix + "_PASSWORD"],
                           database=os.getenv(prefix + "_NAME", default_database), charset="utf8mb4",
                           cursorclass=DictCursor, autocommit=False)


def write_result(result: dict) -> None:
    """Atomically write the capture report."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Capture one operator-named frozen snapshot."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    source = connect("MYTOOLS_LEGACY_DB", "my_tools")
    target = connect("LEGACY_ASSET_ADAPTER_DB", "mytools_legacy_asset_adapter")
    try:
        write_result(capture(source, target, str(parameters["snapshotId"]),
                             int(parameters.get("ownerId") or 0)))
    finally:
        source.close()
        target.close()


if __name__ == "__main__":
    main()
