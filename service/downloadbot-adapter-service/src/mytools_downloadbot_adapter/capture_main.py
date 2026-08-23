"""旧 DownloadBot 只读快照任务入口。"""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile

import pymysql
from pymysql.cursors import DictCursor

from .snapshot_repository import MySqlSnapshotRepository
from .snapshot_service import SnapshotCaptureService


def _connection(prefix: str, default_database: str):
    """从指定环境变量前缀创建 MySQL 连接。"""
    return pymysql.connect(
        host=os.environ.get(f"{prefix}_HOST", "127.0.0.1"),
        port=int(os.environ.get(f"{prefix}_PORT", "3306")),
        user=os.environ[f"{prefix}_USER"], password=os.environ[f"{prefix}_PASSWORD"],
        database=os.environ.get(f"{prefix}_NAME", default_database), charset="utf8mb4",
        cursorclass=DictCursor, autocommit=False)


def main() -> None:
    """执行一次快照捕获并只输出非敏感结果。"""
    legacy_factory = lambda: _connection("DOWNLOADBOT_LEGACY_DB", "downloadbot")
    adapter_factory = lambda: _connection("DOWNLOADBOT_ADAPTER_DB", "mytools_downloadbot_adapter")
    snapshot = SnapshotCaptureService(
        legacy_factory, MySqlSnapshotRepository(adapter_factory)).capture(
            os.environ.get("DOWNLOADBOT_LEGACY_DB_NAME", "downloadbot"))
    result = {"snapshotId": str(snapshot.id), "status": snapshot.status.value,
              "itemCount": snapshot.item_count,
              "rejectionCount": snapshot.rejection_count,
              "collectionSha256": snapshot.collection_sha256}
    result_file = os.environ.get("TASK_RESULT_FILE")
    if not result_file:
        print(json.dumps(result, separators=(",", ":")))
        return
    target = Path(result_file)
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


if __name__ == "__main__":
    main()
