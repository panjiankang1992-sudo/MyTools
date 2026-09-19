"""QQ Gateway 入站事件的本机持久化有序 WAL。"""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass, replace
import errno
import fcntl
import hashlib
import heapq
import json
import math
import os
from pathlib import Path
import re
import stat
import tempfile
import threading
import time
from collections.abc import Callable, Iterator, Mapping
from typing import Any


STATE_PENDING = "PENDING"
STATE_PROCESSING = "PROCESSING"
_STATES = frozenset({STATE_PENDING, STATE_PROCESSING})
_VERSION = 1
_DIGEST = re.compile(r"[0-9a-f]{64}")
_ERROR_CODE = re.compile(r"[A-Z0-9][A-Z0-9_.-]{0,63}")
_EVENT_TYPE = re.compile(r"[A-Z][A-Z0-9_]{0,127}")
_MAX_IDENTIFIER_LENGTH = 2048
_MAX_ATTEMPTS = 1000
_MAX_TIMESTAMP = 253_402_300_799.0
_DEFAULT_ACCOUNT = "default"
_ENTRY_FIELDS = frozenset({
    "version", "kind", "eventKey", "accountKey", "sessionId", "sequence",
    "payload", "payloadDigest", "payloadBytes", "createdAt", "state", "attempt",
    "nextAttemptAt", "lastErrorCode", "leaseUntil", "revision", "recordDigest",
})
_TERMINAL_FIELDS = frozenset({
    "version", "kind", "eventKey", "accountKey", "sessionDigest", "sequence",
    "payloadDigest", "eventType", "messageIdDigest", "attempt", "terminalAt",
    "lastErrorCode", "recordDigest",
})
_RETRY_DEAD_FIELDS = frozenset({
    "version", "kind", "eventKey", "accountKey", "sessionId", "sequence",
    "payload", "payloadDigest", "payloadBytes", "createdAt", "attempt",
    "nextAttemptAt", "lastErrorCode", "terminalAt", "revision", "recordDigest",
})
_CHECKPOINT_FIELDS = frozenset({
    "version", "kind", "accountKey", "sessionId", "sequence", "updatedAt",
    "revision", "recordDigest",
})


class InboundWalError(RuntimeError):
    """表示入站 WAL 操作失败。"""


class InboundWalConflictError(InboundWalError):
    """表示同一幂等键对应了不同载荷或 checkpoint 倒退。"""


class InboundWalStateError(InboundWalError):
    """表示调用方提交了过期的 WAL 状态。"""


class InboundWalSecurityError(InboundWalError):
    """表示 WAL 路径、权限或文件类型不安全。"""


class InboundWalCapacityError(InboundWalError):
    """表示待处理记录或原始载荷超过本机容量上限。"""


class _InvalidRecord(InboundWalError):
    def __init__(self, error_code: str) -> None:
        super().__init__(error_code)
        self.error_code = error_code


@dataclass(frozen=True, slots=True)
class InboundEvent:
    """描述尚未写入 WAL 的原始 Gateway Dispatch。"""

    account_key: str
    session_id: str
    sequence: int
    payload: dict[str, Any]
    payload_digest: str
    payload_bytes: int
    created_at: float

    @classmethod
    def create(cls, account_key: str, session_id: str, sequence: int,
               payload: Mapping[str, Any], now: float | None = None,
               maximum_payload_bytes: int = 16 * 1024 * 1024) -> InboundEvent:
        """校验并复制一条有界的原始 JSON Dispatch。"""
        account = _identifier(account_key, "account_key")
        session = _identifier(session_id, "session_id")
        checked_sequence = _sequence(sequence)
        maximum = _positive_integer(maximum_payload_bytes, "maximum_payload_bytes",
                                    256 * 1024 * 1024)
        encoded = _payload_bytes(payload, maximum)
        copied = json.loads(encoded)
        if not isinstance(copied, dict):
            raise ValueError("payload must be a JSON object")
        return cls(account, session, checked_sequence, copied,
                   hashlib.sha256(encoded).hexdigest(), len(encoded),
                   _timestamp(time.time() if now is None else now, "now"))

    @property
    def event_key(self) -> str:
        """返回不暴露账户和会话内容的稳定幂等键。"""
        return _event_key(self.account_key, self.session_id, self.sequence)


@dataclass(frozen=True, slots=True)
class InboundWalEntry:
    """描述 WAL 中一条可恢复入站事件的当前状态。"""

    event_key: str
    account_key: str
    session_id: str
    sequence: int
    payload: dict[str, Any]
    payload_digest: str
    payload_bytes: int
    created_at: float
    state: str
    attempt: int
    next_attempt_at: float
    last_error_code: str | None
    lease_until: float | None
    revision: int


@dataclass(frozen=True, slots=True)
class GatewayCheckpoint:
    """描述可用于 Gateway Resume 的稳定 checkpoint。"""

    session_id: str
    sequence: int
    account_key: str | None
    updated_at: float
    revision: int


class InboundEventWal:
    """用原子文件记录原始 Dispatch、重试状态与 Gateway checkpoint。"""

    def __init__(self, root: str | os.PathLike[str], *,
                 max_pending_records: int = 10_000,
                 max_payload_bytes: int = 16 * 1024 * 1024,
                 max_total_payload_bytes: int = 128 * 1024 * 1024,
                 max_done_records: int = 10_000,
                 max_dead_records: int = 1_000,
                 clock: Callable[[], float] | None = None) -> None:
        """初始化绝对路径 WAL，并校验所有容量上限。"""
        raw_root = os.fspath(root)
        if not isinstance(raw_root, str) or not os.path.isabs(raw_root):
            raise ValueError("WAL root must be an absolute path")
        self.root = Path(raw_root)
        self.max_pending_records = _positive_integer(
            max_pending_records, "max_pending_records", 1_000_000)
        self.max_payload_bytes = _positive_integer(
            max_payload_bytes, "max_payload_bytes", 256 * 1024 * 1024)
        self.max_total_payload_bytes = _positive_integer(
            max_total_payload_bytes, "max_total_payload_bytes", 4 * 1024 * 1024 * 1024)
        if self.max_total_payload_bytes < self.max_payload_bytes:
            raise ValueError("max_total_payload_bytes must cover one payload")
        self.max_done_records = _positive_integer(
            max_done_records, "max_done_records", 1_000_000)
        self.max_dead_records = _positive_integer(
            max_dead_records, "max_dead_records", 100_000)
        self._clock = time.time if clock is None else clock
        self._thread_lock = threading.RLock()
        self._record_read_limit = self.max_payload_bytes + 128 * 1024
        self._index_ready = False
        self._entries: dict[str, InboundWalEntry] = {}
        self._due_heap: list[tuple[float, str, int]] = []
        self._ready_heap: list[tuple[str, int, str, str, int]] = []
        self._processing_count = 0
        self._pending_payload_bytes = 0
        self._retry_dead_payload_bytes = 0
        self._terminal_counts = {"done": 0, "dead": 0}
        self._terminal_heaps: dict[str, list[tuple[int, int, str]]] = {
            "done": [], "dead": []}
        self._directory_signatures: dict[str, tuple[int, int]] = {}
        self._prepare_layout()
        with self._locked():
            self._recover()
            self._rebuild_hot_index()

    def create_event(self, account_key: str, session_id: str, sequence: int,
                     payload: Mapping[str, Any], now: float | None = None) -> InboundEvent:
        """按本 WAL 的载荷上限创建事件。"""
        return InboundEvent.create(account_key, session_id, sequence, payload, now,
                                   self.max_payload_bytes)

    def store(self, event: InboundEvent) -> bool:
        """幂等保存事件；仍需处理返回真，已有终态或 checkpoint 返回假。"""
        checked = self._validated_event(event)
        name = f"{checked.event_key}.json"
        with self._locked():
            self._ensure_index_current()
            terminal = self._find_terminal(name)
            if terminal is not None:
                self._assert_terminal_matches(checked, terminal)
                return False
            existing = self._entries.get(checked.event_key)
            if existing is not None:
                self._assert_same_event(checked, existing)
                return True
            checkpoint = self._load_checkpoint_unlocked()
            if checkpoint is not None and self._checkpoint_covers(checkpoint, checked):
                return False
            count = len(self._entries)
            payload_bytes = (self._pending_payload_bytes
                             + self._retry_dead_payload_bytes)
            if count >= self.max_pending_records:
                raise InboundWalCapacityError("pending record limit exceeded")
            if payload_bytes + checked.payload_bytes > self.max_total_payload_bytes:
                raise InboundWalCapacityError("pending payload byte limit exceeded")
            entry = InboundWalEntry(
                event_key=checked.event_key,
                account_key=checked.account_key,
                session_id=checked.session_id,
                sequence=checked.sequence,
                payload=checked.payload,
                payload_digest=checked.payload_digest,
                payload_bytes=checked.payload_bytes,
                created_at=checked.created_at,
                state=STATE_PENDING,
                attempt=0,
                next_attempt_at=checked.created_at,
                last_error_code=None,
                lease_until=None,
                revision=0,
            )
            self._atomic_write(self.root / "pending", name, _entry_document(entry))
            self._cache_upsert(entry)
            return True

    def load_batch(self, now: float, limit: int) -> list[InboundWalEntry]:
        """返回所有到期项中按会话和序号排序的前若干项。"""
        checked_now = _timestamp(now, "now")
        checked_limit = _positive_integer(limit, "limit", self.max_pending_records)
        with self._locked():
            self._ensure_index_current()
            entries: list[InboundWalEntry] = []
            while len(entries) < checked_limit:
                entry = self._pop_ready(checked_now)
                if entry is None:
                    break
                entries.append(entry)
            for entry in entries:
                self._push_ready(entry)
            return entries

    def claim_batch(self, now: float, limit: int,
                    lease_seconds: float = 60.0,
                    max_attempts: int = 9) -> list[InboundWalEntry]:
        """原子租约领取到期项，供可能存在多个 worker 的部署使用。"""
        checked_now = _timestamp(now, "now")
        checked_limit = _positive_integer(limit, "limit", self.max_pending_records)
        lease = _positive_number(lease_seconds, "lease_seconds", 3600.0)
        maximum = _positive_integer(max_attempts, "max_attempts", _MAX_ATTEMPTS)
        with self._locked():
            self._ensure_index_current()
            claimed: list[InboundWalEntry] = []
            while len(claimed) < checked_limit:
                entry = self._pop_ready(checked_now)
                if entry is None:
                    break
                try:
                    current = self._current(entry)
                    if current.attempt >= maximum:
                        self._terminalize_retry_dead(
                            current,
                            current.last_error_code or "ATTEMPTS_EXHAUSTED")
                        continue
                    attempt = current.attempt
                    if current.state == STATE_PROCESSING:
                        # 租约过期代表上一次 worker 未能报告结果，
                        # 也必须消耗有限重试预算。
                        attempt = min(attempt + 1, _MAX_ATTEMPTS)
                        if attempt >= maximum:
                            exhausted = replace(
                                current, attempt=attempt,
                                last_error_code="WORKER_LEASE_EXPIRED",
                                revision=current.revision + 1)
                            self._replace_pending(exhausted)
                            self._terminalize_retry_dead(
                                exhausted, "WORKER_LEASE_EXPIRED")
                            continue
                    updated = replace(current, state=STATE_PROCESSING,
                                      attempt=attempt,
                                      lease_until=checked_now + lease,
                                      revision=current.revision + 1)
                    self._replace_pending(updated)
                except Exception:
                    # ready heap 已消费但状态提交未完成时，
                    # 下一次必须从磁盘重建。
                    self._index_ready = False
                    raise
                claimed.append(updated)
            return claimed

    def reschedule(self, entry: InboundWalEntry, next_at: float,
                   error_code: str, max_attempts: int) -> InboundWalEntry | None:
        """记录失败并延后重试；达到有限次数时保留可手工恢复的 dead。"""
        checked_next = _timestamp(next_at, "next_at")
        checked_error = _safe_error_code(error_code)
        maximum = _positive_integer(max_attempts, "max_attempts", _MAX_ATTEMPTS)
        with self._locked():
            self._ensure_index_current()
            current = self._current(entry)
            # 崩溃可能发生在最终 attempt 已写回、dead 摘要尚未落盘之间，
            # 重放时需饱和计数。
            next_attempt = min(current.attempt + 1, _MAX_ATTEMPTS)
            if next_attempt >= maximum:
                failed = replace(current, attempt=next_attempt,
                                 last_error_code=checked_error,
                                 revision=current.revision + 1)
                self._replace_pending(failed)
                self._terminalize_retry_dead(failed, checked_error)
                return None
            updated = replace(current, state=STATE_PENDING,
                              attempt=next_attempt,
                              next_attempt_at=checked_next,
                              last_error_code=checked_error,
                              lease_until=None,
                              revision=current.revision + 1)
            self._replace_pending(updated)
            return updated

    def complete(self, entry: InboundWalEntry) -> None:
        """先持久化有界完成墓碑，再删除含原始正文的 pending 记录。"""
        with self._locked():
            self._ensure_index_current()
            self._terminalize(entry, "done", None)

    def mark_dead(self, entry: InboundWalEntry, error_code: str) -> None:
        """把永久失败事件转换为不含会话和正文的有界安全审计摘要。"""
        checked_error = _safe_error_code(error_code)
        with self._locked():
            self._ensure_index_current()
            self._terminalize(entry, "dead", checked_error)

    def redrive(self, event_key: str, now: float | None = None) -> InboundWalEntry:
        """按精确事件键手工恢复重试耗尽记录；永久坏消息不可恢复。"""
        checked_key = _digest(event_key, "event_key")
        redrive_at = _timestamp(self._clock() if now is None else now, "now")
        name = f"{checked_key}.json"
        with self._locked():
            self._ensure_index_current()
            source = self.root / "dead" / name
            if not _lexists(source):
                raise InboundWalStateError("recoverable dead entry does not exist")
            document = self._read_terminal(source, "dead")
            if document.get("kind") != "INBOUND_RETRY_DEAD":
                raise InboundWalStateError("permanent dead entry cannot be redriven")
            dead_entry = self._retry_dead_entry(document)
            pending_path = self.root / "pending" / name
            done_path = self.root / "done" / name
            if _lexists(pending_path) or _lexists(done_path):
                raise InboundWalConflictError("event already has another WAL state")
            if len(self._entries) >= self.max_pending_records:
                raise InboundWalCapacityError("pending record limit exceeded")
            reset = replace(
                dead_entry, state=STATE_PENDING, attempt=0,
                next_attempt_at=redrive_at, last_error_code=None,
                lease_until=None, revision=dead_entry.revision + 1)
            # 新 pending 先完整落盘；随后删除 dead，
            # 任一时刻至少保留一份稳定记录。
            try:
                self._atomic_write(
                    self.root / "pending", name, _entry_document(reset))
                self._cache_upsert(reset)
                self._unlink_record(source)
            except Exception:
                # 双目录提交中断后强制完整恢复，
                # 禁止热索引暴露两个并存状态。
                self._index_ready = False
                raise
            return reset

    def load_checkpoint(self) -> GatewayCheckpoint | None:
        """读取并校验 Gateway checkpoint；不存在时返回空。"""
        with self._locked():
            return self._load_checkpoint_unlocked()

    def save_checkpoint(self, session_id: str, sequence: int,
                        account_key: str | None = None) -> GatewayCheckpoint:
        """原子保存 checkpoint，并拒绝同一会话中的序号倒退。"""
        session = _identifier(session_id, "session_id")
        checked_sequence = _sequence(sequence)
        account = (None if account_key is None
                   else _identifier(account_key, "account_key"))
        with self._locked():
            current = self._load_checkpoint_unlocked()
            if current is not None and current.session_id == session:
                if checked_sequence < current.sequence:
                    raise InboundWalConflictError(
                        "checkpoint sequence must not move backwards")
                if current.account_key is not None and account is not None \
                        and current.account_key != account:
                    raise InboundWalConflictError("checkpoint account does not match")
                resolved_account = current.account_key if account is None else account
                if checked_sequence == current.sequence \
                        and resolved_account == current.account_key:
                    return current
                revision = current.revision + 1
            else:
                resolved_account = account
                revision = 0 if current is None else current.revision + 1
            checkpoint = GatewayCheckpoint(
                session_id=session,
                sequence=checked_sequence,
                account_key=resolved_account,
                updated_at=_timestamp(self._clock(), "clock"),
                revision=revision,
            )
            self._atomic_write(self.root, "checkpoint.json",
                               _checkpoint_document(checkpoint))
            return checkpoint

    def invalidate_checkpoint(self, session_id: str | None = None) -> bool:
        """持久删除 checkpoint；可用预期会话避免旧连接清除新会话。"""
        expected = None if session_id is None else _identifier(session_id, "session_id")
        with self._locked():
            path = self.root / "checkpoint.json"
            if not _lexists(path):
                return False
            current = self._read_checkpoint(path)
            if expected is not None and current.session_id != expected:
                return False
            try:
                os.unlink(path)
                self._fsync_directory(self.root)
            except OSError as exception:
                raise InboundWalError("unable to invalidate checkpoint") from exception
            return True

    def stats(self) -> dict[str, int]:
        """校验记录并返回不包含正文或会话内容的容量统计。"""
        with self._locked():
            self._ensure_index_current()
            return {
                "pending": len(self._entries) - self._processing_count,
                "processing": self._processing_count,
                "done": self._terminal_counts["done"],
                "dead": self._terminal_counts["dead"],
                "payloadBytes": (self._pending_payload_bytes
                                 + self._retry_dead_payload_bytes),
            }

    def _validated_event(self, event: InboundEvent) -> InboundEvent:
        if not isinstance(event, InboundEvent):
            raise TypeError("event must be InboundEvent")
        recreated = InboundEvent.create(
            event.account_key, event.session_id, event.sequence, event.payload,
            event.created_at, self.max_payload_bytes)
        if recreated.payload_digest != event.payload_digest \
                or recreated.payload_bytes != event.payload_bytes:
            raise ValueError("event payload metadata does not match payload")
        return recreated

    def _prepare_layout(self) -> None:
        if not _lexists(self.root.parent):
            raise InboundWalSecurityError("WAL root parent must already exist")
        try:
            self.root.mkdir(mode=0o700, exist_ok=True)
        except OSError as exception:
            raise InboundWalSecurityError("unable to create WAL root") from exception
        self._secure_directory(self.root)
        # 每次初始化都同步父目录，以覆盖上次进程在 mkdir 与 fsync 之间中断的场景。
        self._fsync_directory(self.root.parent)
        for state in ("pending", "done", "dead"):
            directory = self.root / state
            try:
                directory.mkdir(mode=0o700, exist_ok=True)
            except OSError as exception:
                raise InboundWalSecurityError(
                    "unable to create WAL state directory") from exception
            self._secure_directory(directory)
        lock_path = self.root / ".lock"
        flags = os.O_CREAT | os.O_RDWR
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            descriptor = os.open(lock_path, flags, 0o600)
            try:
                metadata = os.fstat(descriptor)
                if not stat.S_ISREG(metadata.st_mode):
                    raise InboundWalSecurityError("WAL lock must be a regular file")
                os.fchmod(descriptor, 0o600)
            finally:
                os.close(descriptor)
        except InboundWalSecurityError:
            raise
        except OSError as exception:
            raise InboundWalSecurityError("invalid WAL lock file") from exception
        # 每次都同步完整命名空间，覆盖子目录创建完成但根目录 fsync 前中断的场景。
        self._fsync_directory(self.root)

    @staticmethod
    def _secure_directory(path: Path) -> None:
        try:
            metadata = os.lstat(path)
            if stat.S_ISLNK(metadata.st_mode):
                raise InboundWalSecurityError("WAL directory must not be a symlink")
            if not stat.S_ISDIR(metadata.st_mode):
                raise InboundWalSecurityError("WAL path must be a directory")
            flags = os.O_RDONLY
            if hasattr(os, "O_DIRECTORY"):
                flags |= os.O_DIRECTORY
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            descriptor = os.open(path, flags)
            try:
                opened = os.fstat(descriptor)
                if (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino):
                    raise InboundWalSecurityError(
                        "WAL directory changed during validation")
                os.fchmod(descriptor, 0o700)
            finally:
                os.close(descriptor)
        except InboundWalSecurityError:
            raise
        except OSError as exception:
            raise InboundWalSecurityError("invalid WAL directory") from exception

    def _verify_layout(self) -> None:
        self._secure_directory(self.root)
        for state in ("pending", "done", "dead"):
            self._secure_directory(self.root / state)

    @contextmanager
    def _locked(self) -> Iterator[None]:
        with self._thread_lock:
            self._verify_layout()
            flags = os.O_RDWR
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            try:
                descriptor = os.open(self.root / ".lock", flags)
            except OSError as exception:
                raise InboundWalSecurityError("invalid WAL lock file") from exception
            try:
                metadata = os.fstat(descriptor)
                if not stat.S_ISREG(metadata.st_mode):
                    raise InboundWalSecurityError("WAL lock must be a regular file")
                os.fchmod(descriptor, 0o600)
                fcntl.flock(descriptor, fcntl.LOCK_EX)
                self._verify_layout()
                yield
            finally:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
                os.close(descriptor)

    def _recover(self) -> None:
        for state in ("pending", "done", "dead"):
            self._discard_temporaries(self.root / state)
        self._discard_temporaries(self.root)
        checkpoint_path = self.root / "checkpoint.json"
        if _lexists(checkpoint_path):
            self._read_checkpoint(checkpoint_path)
        terminals: dict[str, dict[str, Any]] = {}
        for state in ("done", "dead"):
            for path in tuple(self._record_paths(self.root / state)):
                try:
                    terminal = self._read_terminal(path, state)
                except _InvalidRecord as exception:
                    self._isolate_invalid_terminal(path, exception.error_code)
                    terminal = self._read_terminal(
                        self.root / "dead" / path.name, "dead")
                previous = terminals.get(path.name)
                if previous is not None and (previous["kind"] != terminal["kind"]
                                             or previous["payloadDigest"]
                                             != terminal["payloadDigest"]):
                    raise InboundWalConflictError(
                        "event has conflicting terminal tombstones")
                terminals[path.name] = terminal
        for path in tuple(self._record_paths(self.root / "pending")):
            terminal = terminals.get(path.name)
            if terminal is not None:
                # 终态已先持久化，说明这是跨目录提交在删除原文前
                # 崩溃留下的副本。
                try:
                    pending_entry = self._read_entry(path)
                except _InvalidRecord:
                    self._unlink_invalid(path)
                    continue
                quarantine_digest = hashlib.sha256(b"").hexdigest()
                if terminal["payloadDigest"] not in {
                        pending_entry.payload_digest, quarantine_digest}:
                    raise InboundWalConflictError(
                        "terminal tombstone conflicts with pending entry")
                if terminal["kind"] == "INBOUND_RETRY_DEAD" \
                        and pending_entry.revision > terminal["revision"] \
                        and pending_entry.attempt == 0 \
                        and pending_entry.last_error_code is None:
                    # redrive 先写 pending 后删 dead；这里完成中断的第二步。
                    self._unlink_record(self.root / "dead" / path.name)
                    terminals.pop(path.name, None)
                    continue
                self._unlink_record(path)
                continue
            try:
                self._read_entry(path)
            except _InvalidRecord as exception:
                self._isolate_invalid_pending(path, exception.error_code)
                continue
        self._prune_terminal("done", self.max_done_records)
        self._prune_terminal("dead", self.max_dead_records)
        count, payload_bytes = self._pending_usage(validate_only=True)
        payload_bytes += self._retry_dead_payload_usage()
        if count > self.max_pending_records or payload_bytes > self.max_total_payload_bytes:
            raise InboundWalCapacityError("existing pending WAL exceeds configured capacity")

    def _rebuild_hot_index(self) -> None:
        entries: dict[str, InboundWalEntry] = {}
        due_heap: list[tuple[float, str, int]] = []
        processing = 0
        pending_payload_bytes = 0
        for path in self._record_paths(self.root / "pending"):
            entry = self._read_entry(path)
            entries[entry.event_key] = entry
            due_heap.append((self._entry_due_at(entry), entry.event_key,
                             entry.revision))
            processing += int(entry.state == STATE_PROCESSING)
            pending_payload_bytes += entry.payload_bytes
        terminal_counts = {"done": 0, "dead": 0}
        terminal_heaps: dict[str, list[tuple[int, int, str]]] = {
            "done": [], "dead": []}
        retry_dead_payload_bytes = 0
        for state in ("done", "dead"):
            for path in self._record_paths(self.root / state):
                document = self._read_terminal(path, state)
                terminal_counts[state] += 1
                metadata = os.lstat(path)
                terminal_heaps[state].append((
                    metadata.st_mtime_ns, metadata.st_ino, path.name))
                if document["kind"] == "INBOUND_RETRY_DEAD":
                    retry_dead_payload_bytes += int(document["payloadBytes"])
            heapq.heapify(terminal_heaps[state])
        heapq.heapify(due_heap)
        self._entries = entries
        self._due_heap = due_heap
        self._ready_heap = []
        self._processing_count = processing
        self._pending_payload_bytes = pending_payload_bytes
        self._retry_dead_payload_bytes = retry_dead_payload_bytes
        self._terminal_counts = terminal_counts
        self._terminal_heaps = terminal_heaps
        self._capture_directory_signatures()
        self._index_ready = True

    def _ensure_index_current(self) -> None:
        if not self._index_ready:
            self._recover()
            self._rebuild_hot_index()
            return
        for state in ("pending", "done", "dead"):
            if self._directory_signature(self.root / state) \
                    != self._directory_signatures.get(state):
                # 另一进程或人工运维修改了私有目录，
                # 退回完整校验恢复一次。
                self._index_ready = False
                self._recover()
                self._rebuild_hot_index()
                return

    @staticmethod
    def _entry_due_at(entry: InboundWalEntry) -> float:
        if entry.state == STATE_PROCESSING and entry.lease_until is not None:
            return max(entry.next_attempt_at, entry.lease_until)
        return entry.next_attempt_at

    def _promote_due(self, now: float) -> None:
        while self._due_heap and self._due_heap[0][0] <= now:
            due_at, event_key, revision = heapq.heappop(self._due_heap)
            entry = self._entries.get(event_key)
            if entry is None or entry.revision != revision:
                continue
            if self._entry_due_at(entry) != due_at:
                continue
            self._push_ready(entry)

    def _push_ready(self, entry: InboundWalEntry) -> None:
        heapq.heappush(self._ready_heap, (
            entry.session_id, entry.sequence, entry.account_key,
            entry.event_key, entry.revision))

    def _pop_ready(self, now: float) -> InboundWalEntry | None:
        self._promote_due(now)
        while self._ready_heap:
            *_, event_key, revision = heapq.heappop(self._ready_heap)
            entry = self._entries.get(event_key)
            if entry is None or entry.revision != revision:
                continue
            if self._entry_due_at(entry) > now:
                heapq.heappush(self._due_heap, (
                    self._entry_due_at(entry), event_key, revision))
                continue
            return entry
        return None

    def _cache_upsert(self, entry: InboundWalEntry) -> None:
        if not self._index_ready:
            return
        previous = self._entries.get(entry.event_key)
        if previous is not None:
            self._processing_count -= int(previous.state == STATE_PROCESSING)
            self._pending_payload_bytes -= previous.payload_bytes
        self._entries[entry.event_key] = entry
        self._processing_count += int(entry.state == STATE_PROCESSING)
        self._pending_payload_bytes += entry.payload_bytes
        heapq.heappush(self._due_heap, (
            self._entry_due_at(entry), entry.event_key, entry.revision))
        self._maybe_compact_event_heaps()

    def _cache_remove(self, event_key: str) -> None:
        if not self._index_ready:
            return
        previous = self._entries.pop(event_key, None)
        if previous is not None:
            self._processing_count -= int(previous.state == STATE_PROCESSING)
            self._pending_payload_bytes -= previous.payload_bytes

    def _maybe_compact_event_heaps(self) -> None:
        maximum = max(1024, len(self._entries) * 4)
        if len(self._due_heap) + len(self._ready_heap) <= maximum:
            return
        self._due_heap = [
            (self._entry_due_at(entry), entry.event_key, entry.revision)
            for entry in self._entries.values()]
        heapq.heapify(self._due_heap)
        self._ready_heap = []

    def _maybe_compact_terminal_heap(self, state: str) -> None:
        heap = self._terminal_heaps[state]
        maximum = max(1024, self._terminal_counts[state] * 4)
        if len(heap) <= maximum:
            return
        rebuilt: list[tuple[int, int, str]] = []
        for path in self._record_paths(self.root / state):
            metadata = os.lstat(path)
            rebuilt.append((metadata.st_mtime_ns, metadata.st_ino, path.name))
        heapq.heapify(rebuilt)
        self._terminal_heaps[state] = rebuilt

    def _capture_directory_signatures(self) -> None:
        self._directory_signatures = {
            state: self._directory_signature(self.root / state)
            for state in ("pending", "done", "dead")}

    @staticmethod
    def _directory_signature(path: Path) -> tuple[int, int]:
        metadata = os.stat(path, follow_symlinks=False)
        return metadata.st_mtime_ns, metadata.st_ino

    def _refresh_directory_signature(self, directory: Path) -> None:
        if self._index_ready and directory.name in {"pending", "done", "dead"}:
            self._directory_signatures[directory.name] = \
                self._directory_signature(directory)

    def _pending_usage(self, validate_only: bool = False) -> tuple[int, int]:
        count = 0
        payload_bytes = 0
        for path in tuple(self._record_paths(self.root / "pending")):
            try:
                entry = self._read_entry(path)
            except _InvalidRecord as exception:
                if validate_only:
                    raise
                self._isolate_invalid_pending(path, exception.error_code)
                continue
            count += 1
            payload_bytes += entry.payload_bytes
        return count, payload_bytes

    def _retry_dead_payload_usage(self) -> int:
        payload_bytes = 0
        for path in self._record_paths(self.root / "dead"):
            document = self._read_terminal(path, "dead")
            if document["kind"] == "INBOUND_RETRY_DEAD":
                payload_bytes += int(document["payloadBytes"])
        return payload_bytes

    def _current(self, expected: InboundWalEntry) -> InboundWalEntry:
        if not isinstance(expected, InboundWalEntry):
            raise TypeError("entry must be InboundWalEntry")
        path = self.root / "pending" / f"{expected.event_key}.json"
        if not _lexists(path):
            raise InboundWalStateError("pending entry does not exist")
        try:
            current = self._read_entry(path)
        except _InvalidRecord as exception:
            self._isolate_invalid_pending(path, exception.error_code)
            raise InboundWalStateError("pending entry is invalid") from exception
        if current != expected:
            raise InboundWalStateError("entry revision is stale")
        return current

    def _replace_pending(self, entry: InboundWalEntry) -> None:
        self._atomic_write(self.root / "pending", f"{entry.event_key}.json",
                           _entry_document(entry))
        self._cache_upsert(entry)

    def _terminalize(self, expected: InboundWalEntry, state: str,
                     error_code: str | None) -> None:
        self._ensure_index_current()
        if state not in {"done", "dead"}:
            raise ValueError("invalid terminal state")
        name = f"{expected.event_key}.json"
        existing = self._find_terminal(name)
        pending_path = self.root / "pending" / name
        if existing is not None:
            self._assert_terminal_matches_entry(expected, existing)
            if state == "dead" and existing["kind"] != "INBOUND_DEAD":
                raise InboundWalConflictError("event already completed successfully")
            if state == "done" and existing["kind"] != "INBOUND_DONE":
                raise InboundWalConflictError("event already terminated as dead")
            if _lexists(pending_path):
                try:
                    self._unlink_record(pending_path)
                except Exception:
                    self._index_ready = False
                    raise
            return
        current = self._current(expected)
        if error_code is not None and current.last_error_code != error_code:
            current = replace(current, last_error_code=error_code,
                              revision=current.revision + 1)
            self._replace_pending(current)
        limit = self.max_done_records if state == "done" else self.max_dead_records
        self._prune_terminal(state, limit - 1)
        document = _terminal_document(current, state,
                                      _timestamp(self._clock(), "clock"), error_code)
        try:
            self._atomic_write(self.root / state, name, document)
            # 终态先落盘再清除原文；崩溃留下双份时由 _recover
            # 以终态为准收敛。
            self._unlink_record(pending_path)
        except Exception:
            self._index_ready = False
            raise

    def _terminalize_retry_dead(self, expected: InboundWalEntry,
                                error_code: str) -> None:
        """持久化含原文的有限重试耗尽记录，供运维显式 redrive。"""
        self._ensure_index_current()
        checked_error = _safe_error_code(error_code)
        name = f"{expected.event_key}.json"
        existing = self._find_terminal(name)
        pending_path = self.root / "pending" / name
        if existing is not None:
            self._assert_terminal_matches_entry(expected, existing)
            if existing["kind"] != "INBOUND_RETRY_DEAD":
                raise InboundWalConflictError("event already has another terminal state")
            if _lexists(pending_path):
                try:
                    self._unlink_record(pending_path)
                except Exception:
                    self._index_ready = False
                    raise
            return
        current = self._current(expected)
        if current.last_error_code != checked_error:
            current = replace(
                current, last_error_code=checked_error,
                revision=current.revision + 1)
            self._replace_pending(current)
        self._prune_terminal("dead", self.max_dead_records - 1)
        document = _retry_dead_document(
            current, _timestamp(self._clock(), "clock"))
        try:
            self._atomic_write(self.root / "dead", name, document)
            # dead 原文先落盘再清除 pending，
            # 崩溃双份由恢复逻辑判定终态或 redrive。
            self._unlink_record(pending_path)
        except Exception:
            self._index_ready = False
            raise

    def _find_terminal(self, name: str) -> dict[str, Any] | None:
        found: dict[str, Any] | None = None
        for state in ("done", "dead"):
            path = self.root / state / name
            if not _lexists(path):
                continue
            try:
                terminal = self._read_terminal(path, state)
            except _InvalidRecord as exception:
                self._isolate_invalid_terminal(path, exception.error_code)
                terminal = self._read_terminal(self.root / "dead" / name, "dead")
            if found is not None and found["kind"] != terminal["kind"]:
                raise InboundWalConflictError("event has conflicting terminal tombstones")
            found = terminal
        return found

    @staticmethod
    def _assert_same_event(event: InboundEvent, entry: InboundWalEntry) -> None:
        if event.event_key != entry.event_key or event.account_key != entry.account_key \
                or event.session_id != entry.session_id or event.sequence != entry.sequence:
            raise InboundWalConflictError("idempotency key does not match stored event")
        if event.payload_digest != entry.payload_digest:
            raise InboundWalConflictError("idempotency key has a conflicting payload")

    @staticmethod
    def _assert_terminal_matches(event: InboundEvent,
                                 terminal: Mapping[str, Any]) -> None:
        quarantine_digest = hashlib.sha256(b"").hexdigest()
        if event.event_key != terminal["eventKey"] \
                or terminal["payloadDigest"] not in {
                    event.payload_digest, quarantine_digest}:
            raise InboundWalConflictError("terminal tombstone conflicts with event")

    @staticmethod
    def _assert_terminal_matches_entry(entry: InboundWalEntry,
                                       terminal: Mapping[str, Any]) -> None:
        if entry.event_key != terminal["eventKey"] \
                or entry.payload_digest != terminal["payloadDigest"]:
            raise InboundWalConflictError("terminal tombstone conflicts with entry")

    @staticmethod
    def _checkpoint_covers(checkpoint: GatewayCheckpoint,
                           event: InboundEvent) -> bool:
        account_matches = checkpoint.account_key is None \
            or checkpoint.account_key == event.account_key
        return account_matches and checkpoint.session_id == event.session_id \
            and event.sequence <= checkpoint.sequence

    def _read_entry(self, path: Path) -> InboundWalEntry:
        document = self._read_document(path, self._record_read_limit)
        if frozenset(document) != _ENTRY_FIELDS \
                or document.get("version") != _VERSION \
                or document.get("kind") != "INBOUND_EVENT" \
                or not self._valid_record_digest(document):
            raise _InvalidRecord("WAL_INTEGRITY")
        try:
            account = _identifier(document["accountKey"], "accountKey")
            session = _identifier(document["sessionId"], "sessionId")
            sequence = _sequence(document["sequence"])
            digest = _digest(document["payloadDigest"], "payloadDigest")
            payload_size = _nonnegative_integer(
                document["payloadBytes"], "payloadBytes", self.max_payload_bytes)
            created = _timestamp(document["createdAt"], "createdAt")
            state = _state(document["state"])
            attempt = _nonnegative_integer(
                document["attempt"], "attempt", _MAX_ATTEMPTS)
            next_attempt = _timestamp(document["nextAttemptAt"], "nextAttemptAt")
            error = _optional_error_code(document["lastErrorCode"])
            lease = _optional_timestamp(document["leaseUntil"], "leaseUntil")
            revision = _nonnegative_integer(
                document["revision"], "revision", 2_000_000_000)
            encoded_payload = _payload_bytes(document["payload"], self.max_payload_bytes)
        except (TypeError, ValueError) as exception:
            raise _InvalidRecord("WAL_SCHEMA") from exception
        event_key = _event_key(account, session, sequence)
        if path.name != f"{event_key}.json" \
                or document["eventKey"] != event_key \
                or hashlib.sha256(encoded_payload).hexdigest() != digest \
                or len(encoded_payload) != payload_size \
                or (state == STATE_PENDING and lease is not None) \
                or (state == STATE_PROCESSING and lease is None):
            raise _InvalidRecord("WAL_INTEGRITY")
        payload = json.loads(encoded_payload)
        return InboundWalEntry(event_key, account, session, sequence, payload,
                               digest, payload_size, created, state, attempt,
                               next_attempt, error, lease, revision)

    def _read_terminal(self, path: Path, state: str) -> dict[str, Any]:
        maximum = self._record_read_limit if state == "dead" else 128 * 1024
        document = self._read_document(path, maximum)
        if state == "dead" and document.get("kind") == "INBOUND_RETRY_DEAD":
            self._validate_retry_dead(document, path)
            return document
        expected_kind = "INBOUND_DONE" if state == "done" else "INBOUND_DEAD"
        if frozenset(document) != _TERMINAL_FIELDS \
                or document.get("version") != _VERSION \
                or document.get("kind") != expected_kind \
                or not self._valid_record_digest(document):
            raise _InvalidRecord("WAL_INTEGRITY")
        try:
            event_key = _digest(document["eventKey"], "eventKey")
            _identifier(document["accountKey"], "accountKey")
            _digest(document["sessionDigest"], "sessionDigest")
            _sequence(document["sequence"])
            _digest(document["payloadDigest"], "payloadDigest")
            _event_type(document["eventType"])
            _digest(document["messageIdDigest"], "messageIdDigest")
            _nonnegative_integer(document["attempt"], "attempt", _MAX_ATTEMPTS)
            _timestamp(document["terminalAt"], "terminalAt")
            error = _optional_error_code(document["lastErrorCode"])
        except (TypeError, ValueError) as exception:
            raise _InvalidRecord("WAL_SCHEMA") from exception
        if path.name != f"{event_key}.json" \
                or (state == "dead" and error is None) \
                or (state == "done" and error is not None):
            raise _InvalidRecord("WAL_INTEGRITY")
        return document

    def _validate_retry_dead(self, document: dict[str, Any], path: Path) -> None:
        if frozenset(document) != _RETRY_DEAD_FIELDS \
                or document.get("version") != _VERSION \
                or not self._valid_record_digest(document):
            raise _InvalidRecord("WAL_INTEGRITY")
        try:
            entry = self._retry_dead_entry(document)
            _timestamp(document["terminalAt"], "terminalAt")
        except (TypeError, ValueError) as exception:
            raise _InvalidRecord("WAL_SCHEMA") from exception
        if path.name != f"{entry.event_key}.json" \
                or entry.last_error_code is None or entry.attempt < 1:
            raise _InvalidRecord("WAL_INTEGRITY")

    def _retry_dead_entry(self, document: Mapping[str, Any]) -> InboundWalEntry:
        account = _identifier(document["accountKey"], "accountKey")
        session = _identifier(document["sessionId"], "sessionId")
        sequence = _sequence(document["sequence"])
        event_key = _event_key(account, session, sequence)
        if document["eventKey"] != event_key:
            raise ValueError("eventKey does not match retry dead identity")
        digest = _digest(document["payloadDigest"], "payloadDigest")
        payload_size = _nonnegative_integer(
            document["payloadBytes"], "payloadBytes", self.max_payload_bytes)
        encoded_payload = _payload_bytes(document["payload"], self.max_payload_bytes)
        if hashlib.sha256(encoded_payload).hexdigest() != digest \
                or len(encoded_payload) != payload_size:
            raise ValueError("retry dead payload metadata does not match payload")
        payload = json.loads(encoded_payload)
        return InboundWalEntry(
            event_key, account, session, sequence, payload, digest, payload_size,
            _timestamp(document["createdAt"], "createdAt"), STATE_PENDING,
            _nonnegative_integer(document["attempt"], "attempt", _MAX_ATTEMPTS),
            _timestamp(document["nextAttemptAt"], "nextAttemptAt"),
            _safe_error_code(document["lastErrorCode"]), None,
            _nonnegative_integer(
                document["revision"], "revision", 2_000_000_000))

    def _load_checkpoint_unlocked(self) -> GatewayCheckpoint | None:
        path = self.root / "checkpoint.json"
        if not _lexists(path):
            return None
        return self._read_checkpoint(path)

    def _read_checkpoint(self, path: Path) -> GatewayCheckpoint:
        try:
            document = self._read_document(path, 64 * 1024)
        except _InvalidRecord as exception:
            raise InboundWalSecurityError("invalid Gateway checkpoint") from exception
        if frozenset(document) != _CHECKPOINT_FIELDS \
                or document.get("version") != _VERSION \
                or document.get("kind") != "GATEWAY_CHECKPOINT" \
                or not self._valid_record_digest(document):
            raise InboundWalSecurityError("invalid Gateway checkpoint")
        try:
            account = _optional_identifier(document["accountKey"], "accountKey")
            session = _identifier(document["sessionId"], "sessionId")
            sequence = _sequence(document["sequence"])
            updated = _timestamp(document["updatedAt"], "updatedAt")
            revision = _nonnegative_integer(
                document["revision"], "revision", 2_000_000_000)
        except (TypeError, ValueError) as exception:
            raise InboundWalSecurityError("invalid Gateway checkpoint") from exception
        return GatewayCheckpoint(session, sequence, account, updated, revision)

    def _read_document(self, path: Path, maximum_bytes: int) -> dict[str, Any]:
        flags = os.O_RDONLY
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            before = os.lstat(path)
            if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode):
                raise _InvalidRecord("WAL_FILE_TYPE")
            descriptor = os.open(path, flags)
            try:
                opened = os.fstat(descriptor)
                if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino) \
                        or not stat.S_ISREG(opened.st_mode):
                    raise _InvalidRecord("WAL_FILE_TYPE")
                if stat.S_IMODE(opened.st_mode) != 0o600:
                    raise _InvalidRecord("WAL_PERMISSION")
                chunks: list[bytes] = []
                size = 0
                while True:
                    chunk = os.read(descriptor, min(64 * 1024, maximum_bytes + 1 - size))
                    if not chunk:
                        break
                    chunks.append(chunk)
                    size += len(chunk)
                    if size > maximum_bytes:
                        raise _InvalidRecord("WAL_RECORD_TOO_LARGE")
            finally:
                os.close(descriptor)
        except _InvalidRecord:
            raise
        except OSError as exception:
            raise _InvalidRecord("WAL_READ_FAILED") from exception
        try:
            document = json.loads(b"".join(chunks))
        except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as exception:
            raise _InvalidRecord("WAL_JSON") from exception
        if not isinstance(document, dict):
            raise _InvalidRecord("WAL_SCHEMA")
        return document

    @staticmethod
    def _valid_record_digest(document: Mapping[str, Any]) -> bool:
        digest = document.get("recordDigest")
        if not isinstance(digest, str) or not _DIGEST.fullmatch(digest):
            return False
        unsigned = dict(document)
        del unsigned["recordDigest"]
        try:
            actual = hashlib.sha256(_canonical_bytes(unsigned)).hexdigest()
        except (TypeError, ValueError, RecursionError, OverflowError):
            return False
        return actual == digest

    def _isolate_invalid_pending(self, path: Path, error_code: str) -> None:
        event_key = _safe_path_key(path)
        self._write_quarantine(event_key, error_code)
        self._unlink_invalid(path)

    def _isolate_invalid_terminal(self, path: Path, error_code: str) -> None:
        event_key = _safe_path_key(path)
        self._unlink_invalid(path)
        self._write_quarantine(event_key, error_code)

    def _write_quarantine(self, event_key: str, error_code: str) -> None:
        checked_key = _digest(event_key, "event_key")
        self._prune_terminal("dead", self.max_dead_records - 1)
        now = _timestamp(self._clock(), "clock")
        document: dict[str, Any] = {
            "version": _VERSION,
            "kind": "INBOUND_DEAD",
            "eventKey": checked_key,
            "accountKey": _DEFAULT_ACCOUNT,
            "sessionDigest": hashlib.sha256(b"").hexdigest(),
            "sequence": 0,
            "payloadDigest": hashlib.sha256(b"").hexdigest(),
            "eventType": "UNKNOWN",
            "messageIdDigest": hashlib.sha256(b"").hexdigest(),
            "attempt": 0,
            "terminalAt": now,
            "lastErrorCode": _safe_error_code(error_code),
        }
        document["recordDigest"] = hashlib.sha256(
            _canonical_bytes(document)).hexdigest()
        self._atomic_write(self.root / "dead", f"{checked_key}.json", document)

    def _prune_terminal(self, state: str, maximum: int) -> None:
        if self._index_ready:
            target = max(0, maximum)
            heap = self._terminal_heaps[state]
            protected: list[tuple[int, int, str]] = []
            try:
                while self._terminal_counts[state] > target:
                    while heap:
                        modified_at, inode, name = heapq.heappop(heap)
                        path = self.root / state / name
                        if not _lexists(path):
                            continue
                        metadata = os.lstat(path)
                        if metadata.st_mtime_ns != modified_at \
                                or metadata.st_ino != inode:
                            continue
                        if state == "dead" and self._read_terminal(
                                path, state)["kind"] == "INBOUND_RETRY_DEAD":
                            # 含原文的可恢复 dead 不得因容量裁剪而永久丢失。
                            protected.append((modified_at, inode, name))
                            continue
                        self._unlink_record(path)
                        break
                    else:
                        if state == "dead" \
                                and len(protected) == self._terminal_counts[state]:
                            raise InboundWalCapacityError(
                                "recoverable dead record limit exceeded")
                        # 索引不一致时失败关闭，下一次操作执行完整恢复。
                        self._index_ready = False
                        raise InboundWalStateError("terminal index is inconsistent")
            finally:
                for item in protected:
                    heapq.heappush(heap, item)
            return
        paths = self._record_paths(self.root / state)
        excess = len(paths) - max(0, maximum)
        if excess <= 0:
            return
        try:
            ordered = sorted(paths, key=lambda path: (os.lstat(path).st_mtime_ns, path.name))
            if state == "dead":
                ordered = [path for path in ordered if self._read_terminal(
                    path, state)["kind"] != "INBOUND_RETRY_DEAD"]
            if len(ordered) < excess:
                raise InboundWalCapacityError(
                    "recoverable dead record limit exceeded")
            for path in ordered[:excess]:
                self._unlink_record(path)
        except OSError as exception:
            raise InboundWalError("unable to prune terminal WAL records") from exception

    def _record_paths(self, directory: Path) -> list[Path]:
        result: list[Path] = []
        try:
            paths = tuple(directory.iterdir())
        except OSError as exception:
            raise InboundWalSecurityError("unable to enumerate WAL directory") from exception
        for path in paths:
            if path.name.startswith(".tmp-"):
                self._discard_temporary(path)
                continue
            if not re.fullmatch(r"[0-9a-f]{64}\.json", path.name):
                raise InboundWalSecurityError("unexpected WAL directory entry")
            result.append(path)
        return result

    def _discard_temporaries(self, directory: Path) -> None:
        try:
            paths = tuple(directory.iterdir())
        except OSError as exception:
            raise InboundWalSecurityError("unable to enumerate WAL directory") from exception
        for path in paths:
            if path.name.startswith(".tmp-"):
                self._discard_temporary(path)

    @staticmethod
    def _discard_temporary(path: Path) -> None:
        try:
            metadata = os.lstat(path)
            if not (stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode)):
                raise InboundWalSecurityError("temporary WAL entry has unsafe type")
            os.unlink(path)
        except FileNotFoundError:
            return
        except InboundWalSecurityError:
            raise
        except OSError as exception:
            raise InboundWalSecurityError("unable to remove temporary WAL entry") from exception

    def _unlink_invalid(self, path: Path) -> None:
        try:
            metadata = os.lstat(path)
            if not (stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode)):
                raise InboundWalSecurityError("invalid WAL entry has unsafe type")
            os.unlink(path)
            self._fsync_directory(path.parent)
            self._refresh_directory_signature(path.parent)
            if self._index_ready:
                self._index_ready = False
        except FileNotFoundError:
            return
        except InboundWalSecurityError:
            raise
        except OSError as exception:
            raise InboundWalSecurityError("unable to isolate invalid WAL entry") from exception

    def _unlink_record(self, path: Path) -> None:
        terminal_document: dict[str, Any] | None = None
        try:
            metadata = os.lstat(path)
            if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
                raise InboundWalSecurityError("WAL record must be a regular file")
            if self._index_ready and path.parent.name in {"done", "dead"}:
                terminal_document = self._read_terminal(path, path.parent.name)
            os.unlink(path)
            self._fsync_directory(path.parent)
            if self._index_ready:
                if path.parent.name == "pending" and _DIGEST.fullmatch(path.stem):
                    self._cache_remove(path.stem)
                elif path.parent.name in {"done", "dead"}:
                    self._terminal_counts[path.parent.name] -= 1
                    if terminal_document is not None \
                            and terminal_document["kind"] == "INBOUND_RETRY_DEAD":
                        self._retry_dead_payload_bytes -= int(
                            terminal_document["payloadBytes"])
            self._refresh_directory_signature(path.parent)
        except FileNotFoundError:
            return
        except InboundWalSecurityError:
            raise
        except OSError as exception:
            raise InboundWalError("unable to remove WAL record") from exception

    def _atomic_write(self, directory: Path, name: str,
                      document: Mapping[str, Any]) -> None:
        data = _canonical_bytes(document) + b"\n"
        target = directory / name
        existed = _lexists(target)
        if existed:
            try:
                metadata = os.lstat(target)
            except OSError as exception:
                raise InboundWalSecurityError("unable to inspect WAL target") from exception
            if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
                raise InboundWalSecurityError("WAL target must be a regular file")
        descriptor = -1
        temporary = ""
        try:
            descriptor, temporary = tempfile.mkstemp(prefix=".tmp-", dir=directory)
            os.fchmod(descriptor, 0o600)
            offset = 0
            while offset < len(data):
                written = os.write(descriptor, data[offset:])
                if written <= 0:
                    raise OSError(errno.EIO, "short WAL write")
                offset += written
            os.fsync(descriptor)
            os.close(descriptor)
            descriptor = -1
            os.replace(temporary, target)
            temporary = ""
            self._fsync_directory(directory)
            if self._index_ready and directory.name in {"done", "dead"} \
                    and not existed:
                self._terminal_counts[directory.name] += 1
                metadata = os.lstat(target)
                heapq.heappush(
                    self._terminal_heaps[directory.name],
                    (metadata.st_mtime_ns, metadata.st_ino, name))
                if document.get("kind") == "INBOUND_RETRY_DEAD":
                    self._retry_dead_payload_bytes += int(document["payloadBytes"])
                self._maybe_compact_terminal_heap(directory.name)
            self._refresh_directory_signature(directory)
        except OSError as exception:
            raise InboundWalError("unable to persist WAL record") from exception
        finally:
            if descriptor >= 0:
                os.close(descriptor)
            if temporary:
                try:
                    os.unlink(temporary)
                except FileNotFoundError:
                    pass

    @staticmethod
    def _fsync_directory(directory: Path) -> None:
        flags = os.O_RDONLY
        if hasattr(os, "O_DIRECTORY"):
            flags |= os.O_DIRECTORY
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            metadata = os.lstat(directory)
            if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
                raise InboundWalSecurityError("WAL fsync target must be a directory")
            descriptor = os.open(directory, flags)
            try:
                opened = os.fstat(descriptor)
                if not stat.S_ISDIR(opened.st_mode) \
                        or (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino):
                    raise InboundWalSecurityError(
                        "WAL fsync directory changed during validation")
                try:
                    os.fsync(descriptor)
                except OSError as exception:
                    if exception.errno not in {errno.EINVAL, errno.ENOTSUP}:
                        raise
            finally:
                os.close(descriptor)
        except InboundWalSecurityError:
            raise
        except OSError as exception:
            raise InboundWalSecurityError("invalid WAL fsync directory") from exception


# 兼容偏短的集成命名，同时保留语义更明确的主类名。
InboundWal = InboundEventWal


def _entry_document(entry: InboundWalEntry) -> dict[str, Any]:
    document: dict[str, Any] = {
        "version": _VERSION,
        "kind": "INBOUND_EVENT",
        "eventKey": entry.event_key,
        "accountKey": entry.account_key,
        "sessionId": entry.session_id,
        "sequence": entry.sequence,
        "payload": entry.payload,
        "payloadDigest": entry.payload_digest,
        "payloadBytes": entry.payload_bytes,
        "createdAt": entry.created_at,
        "state": entry.state,
        "attempt": entry.attempt,
        "nextAttemptAt": entry.next_attempt_at,
        "lastErrorCode": entry.last_error_code,
        "leaseUntil": entry.lease_until,
        "revision": entry.revision,
    }
    document["recordDigest"] = hashlib.sha256(_canonical_bytes(document)).hexdigest()
    return document


def _terminal_document(entry: InboundWalEntry, state: str, terminal_at: float,
                       error_code: str | None) -> dict[str, Any]:
    event_type = str(entry.payload.get("t") or "UNKNOWN")
    if not _EVENT_TYPE.fullmatch(event_type):
        event_type = "UNKNOWN"
    data = entry.payload.get("d")
    message_id = str(data.get("id") or "") if isinstance(data, dict) else ""
    document: dict[str, Any] = {
        "version": _VERSION,
        "kind": "INBOUND_DONE" if state == "done" else "INBOUND_DEAD",
        "eventKey": entry.event_key,
        "accountKey": entry.account_key,
        "sessionDigest": hashlib.sha256(entry.session_id.encode("utf-8")).hexdigest(),
        "sequence": entry.sequence,
        "payloadDigest": entry.payload_digest,
        "eventType": event_type,
        "messageIdDigest": hashlib.sha256(message_id.encode("utf-8")).hexdigest(),
        "attempt": entry.attempt,
        "terminalAt": terminal_at,
        "lastErrorCode": error_code,
    }
    document["recordDigest"] = hashlib.sha256(_canonical_bytes(document)).hexdigest()
    return document


def _retry_dead_document(entry: InboundWalEntry,
                         terminal_at: float) -> dict[str, Any]:
    document: dict[str, Any] = {
        "version": _VERSION,
        "kind": "INBOUND_RETRY_DEAD",
        "eventKey": entry.event_key,
        "accountKey": entry.account_key,
        "sessionId": entry.session_id,
        "sequence": entry.sequence,
        "payload": entry.payload,
        "payloadDigest": entry.payload_digest,
        "payloadBytes": entry.payload_bytes,
        "createdAt": entry.created_at,
        "attempt": entry.attempt,
        "nextAttemptAt": entry.next_attempt_at,
        "lastErrorCode": entry.last_error_code,
        "terminalAt": terminal_at,
        "revision": entry.revision,
    }
    document["recordDigest"] = hashlib.sha256(_canonical_bytes(document)).hexdigest()
    return document


def _checkpoint_document(checkpoint: GatewayCheckpoint) -> dict[str, Any]:
    document: dict[str, Any] = {
        "version": _VERSION,
        "kind": "GATEWAY_CHECKPOINT",
        "accountKey": checkpoint.account_key,
        "sessionId": checkpoint.session_id,
        "sequence": checkpoint.sequence,
        "updatedAt": checkpoint.updated_at,
        "revision": checkpoint.revision,
    }
    document["recordDigest"] = hashlib.sha256(_canonical_bytes(document)).hexdigest()
    return document


def _canonical_bytes(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      allow_nan=False).encode("utf-8")


def _payload_bytes(payload: object, maximum_bytes: int) -> bytes:
    if not isinstance(payload, Mapping):
        raise ValueError("payload must be a JSON object")
    try:
        encoded = _canonical_bytes(payload)
    except (TypeError, ValueError, RecursionError, OverflowError) as exception:
        raise ValueError("payload must be JSON compatible") from exception
    if len(encoded) > maximum_bytes:
        raise ValueError("payload exceeds maximum_payload_bytes")
    return encoded


def _event_key(account_key: str, session_id: str, sequence: int) -> str:
    identity = f"{account_key}\0{session_id}\0{sequence}".encode("utf-8")
    return hashlib.sha256(identity).hexdigest()


def _safe_path_key(path: Path) -> str:
    if path.suffix == ".json" and _DIGEST.fullmatch(path.stem):
        return path.stem
    return hashlib.sha256(path.name.encode("utf-8", errors="replace")).hexdigest()


def _identifier(value: object, name: str) -> str:
    if not isinstance(value, str) or not value or value != value.strip() \
            or len(value) > _MAX_IDENTIFIER_LENGTH \
            or any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise ValueError(f"{name} must be a non-blank bounded string")
    return value


def _optional_identifier(value: object, name: str) -> str | None:
    if value is None:
        return None
    return _identifier(value, name)


def _digest(value: object, name: str) -> str:
    if not isinstance(value, str) or not _DIGEST.fullmatch(value):
        raise ValueError(f"{name} must be a SHA-256 digest")
    return value


def _sequence(value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, int) \
            or value < 0 or value > 9_223_372_036_854_775_807:
        raise ValueError("sequence must be a non-negative signed 64-bit integer")
    return value


def _timestamp(value: object, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError(f"{name} must be numeric")
    checked = float(value)
    if not math.isfinite(checked) or checked < 0 or checked > _MAX_TIMESTAMP:
        raise ValueError(f"{name} must be a finite non-negative timestamp")
    return checked


def _optional_timestamp(value: object, name: str) -> float | None:
    if value is None:
        return None
    return _timestamp(value, name)


def _positive_number(value: object, name: str, maximum: float) -> float:
    checked = _timestamp(value, name)
    if checked <= 0 or checked > maximum:
        raise ValueError(f"{name} is outside the allowed range")
    return checked


def _positive_integer(value: object, name: str, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) \
            or value <= 0 or value > maximum:
        raise ValueError(f"{name} must be a positive bounded integer")
    return value


def _nonnegative_integer(value: object, name: str, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) \
            or value < 0 or value > maximum:
        raise ValueError(f"{name} must be a bounded non-negative integer")
    return value


def _state(value: object) -> str:
    if value not in _STATES:
        raise ValueError("state is invalid")
    return str(value)


def _event_type(value: object) -> str:
    if not isinstance(value, str) or not _EVENT_TYPE.fullmatch(value):
        raise ValueError("eventType is invalid")
    return value


def _safe_error_code(value: object) -> str:
    if not isinstance(value, str):
        raise TypeError("error_code must be a string")
    if len(value) > 64:
        raise ValueError("error_code is too long")
    if not _ERROR_CODE.fullmatch(value):
        raise ValueError("error_code contains unsafe characters")
    return value


def _optional_error_code(value: object) -> str | None:
    if value is None:
        return None
    return _safe_error_code(value)


def _lexists(path: Path) -> bool:
    return os.path.lexists(path)
