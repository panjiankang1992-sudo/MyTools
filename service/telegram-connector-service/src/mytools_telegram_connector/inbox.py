"""在确认 Telegram 更新前持久化入站消息。"""
from __future__ import annotations

from dataclasses import dataclass
import fcntl
import json
import os
from pathlib import Path
import sqlite3
import stat
import time
from typing import Any


ALBUM_WINDOW_SECONDS = 2
RECEIPT_RETENTION_SECONDS = 48 * 60 * 60
MAXIMUM_INBOX_BYTES = 128 * 1024 * 1024
MAXIMUM_PENDING_GROUPS = 10_000


@dataclass(frozen=True, slots=True)
class Delivery:
    """投递开始后不再接受新成员的一组更新。"""

    id: int
    kind: str
    album: str
    messages: list[dict[str, Any]]
    attempts: int


class Inbox:
    """以 SQLite 事务保存更新收据、媒体相册和投递状态。"""

    def __init__(self, path: str, account_identity: str) -> None:
        self.fd: int | None = None
        if path != ":memory:":
            db_file = Path(path)
            if not db_file.is_absolute():
                raise ValueError("Telegram inbox path must be absolute")
            db_file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            parent = db_file.parent.lstat()
            if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o077:
                raise ValueError("Telegram inbox directory must be private")
            fd = os.open(db_file, os.O_CREAT | os.O_RDONLY | os.O_NOFOLLOW, 0o600)
            try:
                info = os.fstat(fd)
                if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                        or info.st_mode & 0o077 or info.st_nlink != 1):
                    raise ValueError("Telegram inbox file must be private")
            finally:
                os.close(fd)
            lock = os.open(db_file.with_name(db_file.name + ".lock"),
                           os.O_CREAT | os.O_RDONLY | os.O_NOFOLLOW, 0o600)
            try:
                info = os.fstat(lock)
                if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                        or info.st_mode & 0o077 or info.st_nlink != 1):
                    raise ValueError("Telegram inbox lock must be private")
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BaseException:
                os.close(lock)
                raise
            self.fd = lock
        try:
            self.db = sqlite3.connect(path)
        except BaseException:
            if self.fd is not None:
                os.close(self.fd)
            raise
        try:
            self.db.execute("PRAGMA synchronous = FULL")
            self.db.executescript("""
            CREATE TABLE IF NOT EXISTS identity (
                singleton INTEGER PRIMARY KEY CHECK (singleton = 1), value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS receipt (
                update_id INTEGER PRIMARY KEY, stored_at REAL NOT NULL);
            CREATE INDEX IF NOT EXISTS receipt_expiry ON receipt (stored_at);
            CREATE TABLE IF NOT EXISTS delivery (
                id INTEGER PRIMARY KEY, kind TEXT NOT NULL, chat TEXT NOT NULL, album TEXT NOT NULL,
                messages BLOB NOT NULL, created_at REAL NOT NULL, next_attempt_at REAL NOT NULL,
                sealed INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0,
                relay_started INTEGER NOT NULL DEFAULT 0, dead_reason TEXT);
            CREATE INDEX IF NOT EXISTS delivery_due ON delivery (dead_reason, next_attempt_at, id);
            CREATE INDEX IF NOT EXISTS delivery_album ON delivery (chat, album, sealed, id);
            CREATE TABLE IF NOT EXISTS business_connection (
                singleton INTEGER PRIMARY KEY CHECK (singleton = 1), value TEXT NOT NULL);
            """)
            with self.db:
                self.db.execute("INSERT OR IGNORE INTO identity VALUES (1, ?)", (account_identity,))
                if self.db.execute("SELECT value FROM identity WHERE singleton=1").fetchone() != (account_identity,):
                    raise ValueError("Telegram inbox belongs to a different account")
                # 已发起非幂等 Business 转发时，进程中断可能已被 Telegram 接受。
                self.db.execute("""UPDATE delivery SET dead_reason='RELAY_UNCERTAIN'
                    WHERE relay_started=1 AND dead_reason IS NULL""")
        except BaseException:
            self.close()
            raise

    def close(self) -> None:
        """释放本地数据库连接。"""
        self.db.close()
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None

    def business_connection(self, default: str) -> str:
        """恢复上一次更新的 Business 连接。"""
        row = self.db.execute("SELECT value FROM business_connection WHERE singleton=1").fetchone()
        return default if row is None else row[0]

    def save_business_connection(self, value: str) -> None:
        """保存当前 Business 连接状态，空值表示已被禁用。"""
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO business_connection VALUES (1, ?)", (value,))

    def store(self, updates: list[tuple[int, tuple[str, str, str, dict[str, Any]] | None]]) -> None:
        """原子写入整批收据，按会话及相册聚合尚未开始投递的记录。"""
        now = time.time()
        with self.db:
            self.db.execute("DELETE FROM receipt WHERE stored_at < ?", (now - RECEIPT_RETENTION_SECONDS,))
            for update_id, accepted in updates:
                inserted = self.db.execute("INSERT OR IGNORE INTO receipt VALUES (?, ?)", (update_id, now))
                if not inserted.rowcount or accepted is None:
                    continue
                kind, chat, album, message = accepted
                current = None
                if album:
                    current = self.db.execute("""SELECT id, messages FROM delivery
                        WHERE chat=? AND album=? AND sealed=0 AND dead_reason IS NULL
                        ORDER BY id DESC LIMIT 1""", (chat, album)).fetchone()
                if current is not None:
                    messages = json.loads(current[1])
                    messages.append(message)
                    self.db.execute("UPDATE delivery SET messages=?, next_attempt_at=? WHERE id=?",
                                    (self._encode(messages), now + ALBUM_WINDOW_SECONDS, current[0]))
                else:
                    self.db.execute("""INSERT INTO delivery (kind, chat, album, messages, created_at, next_attempt_at)
                        VALUES (?, ?, ?, ?, ?, ?)""", (kind, chat, album, self._encode([message]), now,
                                                        now + ALBUM_WINDOW_SECONDS if album else now))
            count, size = self.db.execute("SELECT COUNT(*), COALESCE(SUM(LENGTH(messages)),0) FROM delivery").fetchone()
            if count > MAXIMUM_PENDING_GROUPS or size > MAXIMUM_INBOX_BYTES:
                raise RuntimeError("Telegram inbox capacity exceeded")

    @staticmethod
    def _encode(messages: list[dict[str, Any]]) -> bytes:
        return json.dumps(messages, ensure_ascii=False, separators=(",", ":")).encode()

    def claim(self) -> Delivery | None:
        """取出最早可处理的记录，固定本次投递的相册成员。"""
        with self.db:
            row = self.db.execute("""SELECT id, kind, album, messages, attempts FROM delivery
                WHERE dead_reason IS NULL AND next_attempt_at <= ?
                ORDER BY next_attempt_at, id LIMIT 1""", (time.time(),)).fetchone()
            if row is None:
                return None
            self.db.execute("UPDATE delivery SET sealed=1 WHERE id=?", (row[0],))
            return Delivery(row[0], row[1], row[2], json.loads(row[3]), row[4])

    def relay_started(self, delivery_id: int) -> None:
        """在首次 Business 发送前记录远端可能已收到请求。"""
        with self.db:
            self.db.execute("UPDATE delivery SET relay_started=1 WHERE id=?", (delivery_id,))

    def complete(self, delivery_id: int) -> None:
        """删除已投递的正文，保留限时去重收据。"""
        with self.db:
            self.db.execute("DELETE FROM delivery WHERE id=?", (delivery_id,))

    def fail(self, delivery: Delivery, error_code: str, permanent: bool) -> None:
        """保留失败消息，退避重试或标记需人工核对的记录。"""
        delay = min(2 ** min(delivery.attempts, 6), 60)
        with self.db:
            self.db.execute("""UPDATE delivery SET attempts=attempts+1, next_attempt_at=?,
                dead_reason=CASE WHEN relay_started=1 THEN 'RELAY_UNCERTAIN'
                                 WHEN ? THEN ? ELSE NULL END WHERE id=?""",
                            (time.time() + delay, permanent, error_code, delivery.id))

    def health(self) -> tuple[int, int, int]:
        """仅返回待投递数量、最老记录年龄和终止失败数量。"""
        pending, oldest, dead = self.db.execute("""SELECT
            COUNT(CASE WHEN dead_reason IS NULL THEN 1 END),
            MIN(CASE WHEN dead_reason IS NULL THEN created_at END),
            COUNT(dead_reason) FROM delivery""").fetchone()
        return pending, max(0, int(time.time() - oldest)) if oldest is not None else 0, dead
