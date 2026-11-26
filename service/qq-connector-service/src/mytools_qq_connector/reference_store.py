"""按账户和会话隔离、跨重启保存的官方 QQ 引用消息缓存。"""
from __future__ import annotations

from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import time
from typing import Any

from .message_parser import message_indices


class MessageReferenceStore:
    """有界保存实际收到的消息；缓存不承担入站投递的真源职责。"""

    def __init__(self, path: str | Path, max_entries: int = 4096,
                 max_bytes: int = 64 * 1024 * 1024, ttl_seconds: float = 7 * 86400,
                 max_message_bytes: int = 256 * 1024) -> None:
        if min(max_entries, max_bytes, ttl_seconds, max_message_bytes) <= 0:
            raise ValueError("invalid QQ reference cache limits")
        self.path = Path(path)
        self.max_entries = max_entries
        self.max_bytes = max_bytes
        self.ttl_seconds = ttl_seconds
        self.max_message_bytes = max_message_bytes
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        # 缓存含用户正文，文件必须仅当前运行账户可读写。
        descriptor = os.open(self.path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        os.fchmod(descriptor, 0o600)
        os.close(descriptor)
        with closing(sqlite3.connect(self.path)) as connection, connection:
            connection.execute("""CREATE TABLE IF NOT EXISTS message_references (
                scope TEXT NOT NULL, ref_key TEXT NOT NULL, payload TEXT NOT NULL,
                payload_bytes INTEGER NOT NULL, created_at REAL NOT NULL,
                PRIMARY KEY (scope, ref_key))""")
            connection.execute("""CREATE INDEX IF NOT EXISTS reference_age
                                  ON message_references(created_at)""")

    def remember(self, scope: str, message: dict[str, Any]) -> None:
        """记录事件自身索引对应的原始结构，不把引用索引误当作自身索引。"""
        own, _ = message_indices(message)
        key = own or message.get("id")
        if not isinstance(key, str) or not key or len(key) > 2048:
            return
        try:
            payload = json.dumps(message, ensure_ascii=True, separators=(",", ":"), allow_nan=False)
        except (ValueError, RecursionError):
            return
        size = len(payload.encode("utf-8"))
        # 不缓存残缺摘要，超大消息后续引用会明确报告不可恢复。
        if size > min(self.max_message_bytes, self.max_bytes):
            return
        now = time.time()
        with closing(sqlite3.connect(self.path, timeout=10)) as connection, connection:
            connection.execute("DELETE FROM message_references WHERE created_at < ?",
                               (now - self.ttl_seconds,))
            # 重放不覆盖已保存的原文，也不延长保留期限。
            connection.execute("""INSERT OR IGNORE INTO message_references
                (scope, ref_key, payload, payload_bytes, created_at) VALUES (?, ?, ?, ?, ?)""",
                               (scope, key, payload, size, now))
            count, total = connection.execute(
                "SELECT COUNT(*), COALESCE(SUM(payload_bytes), 0) FROM message_references").fetchone()
            if count > self.max_entries or total > self.max_bytes:
                rows = connection.execute("""SELECT rowid, payload_bytes FROM message_references
                                             ORDER BY created_at, rowid""").fetchall()
                for row_id, payload_size in rows:
                    if count <= self.max_entries and total <= self.max_bytes:
                        break
                    connection.execute("DELETE FROM message_references WHERE rowid = ?", (row_id,))
                    count -= 1
                    total -= payload_size

    def get(self, scope: str, key: str) -> dict[str, Any] | None:
        """只查同一账户和会话中尚未过期的已接收原文。"""
        with closing(sqlite3.connect(self.path, timeout=10)) as connection:
            row = connection.execute("""SELECT payload FROM message_references
                WHERE scope = ? AND ref_key = ? AND created_at >= ?""",
                                     (scope, key, time.time() - self.ttl_seconds)).fetchone()
        return json.loads(row[0]) if row else None
