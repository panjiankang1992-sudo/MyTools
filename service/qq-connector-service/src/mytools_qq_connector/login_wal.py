"""QQ 登录控制命令的原子文件 WAL。"""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass, replace
import errno
import fcntl
import hashlib
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


PHASE_EXECUTE = "EXECUTE"
PHASE_FAILURE_REPLY = "FAILURE_REPLY"
_PHASES = frozenset({PHASE_EXECUTE, PHASE_FAILURE_REPLY})
_ERROR_CODE = re.compile(r"[A-Z0-9][A-Z0-9_.-]{0,63}")
_DIGEST = re.compile(r"[0-9a-f]{64}")
_MAX_RECORD_BYTES = 64 * 1024
_MAX_IDENTIFIER_LENGTH = 512
_VERSION = 1
_ENTRY_FIELDS = frozenset({
    "version", "kind", "accountKey", "messageId", "senderId", "payloadDigest",
    "createdAt", "deadlineAt", "phase", "taskInstanceId", "attempt",
    "nextAttemptAt", "lastErrorCode", "revision", "recordDigest",
})
_ENTRY_FIELDS_V2 = _ENTRY_FIELDS | {"generation"}
_QUARANTINE_FIELDS = frozenset({
    "version", "kind", "eventKey", "quarantinedAt", "lastErrorCode", "recordDigest",
})
_REJECTION_FIELDS = frozenset({
    "version", "kind", "eventKey", "accountKey", "eventType", "messageIdDigest",
    "sequenceNumber", "rejectedAt", "lastErrorCode", "recordDigest",
})
_EVENT_TYPE = re.compile(r"[A-Z][A-Z0-9_]{0,127}")


class LoginWalError(RuntimeError):
    """表示登录命令 WAL 操作失败。"""


class LoginWalConflictError(LoginWalError):
    """表示相同幂等键对应了不同命令。"""


class LoginWalStateError(LoginWalError):
    """表示调用方使用了过期或非法状态。"""


class LoginWalSecurityError(LoginWalError):
    """表示 WAL 路径或文件未满足安全约束。"""


class _InvalidRecord(LoginWalError):
    def __init__(self, error_code: str) -> None:
        super().__init__(error_code)
        self.error_code = error_code


@dataclass(frozen=True, slots=True)
class LoginCommand:
    """描述尚未写入 WAL 的登录命令。"""

    account_key: str
    message_id: str
    sender_id: str
    payload_digest: str
    created_at: float
    deadline_at: float

    @staticmethod
    def digest(payload: object) -> str:
        """对 JSON 兼容载荷计算稳定的 SHA-256 摘要。"""
        try:
            encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True,
                                 separators=(",", ":"), allow_nan=False).encode("utf-8")
        except (TypeError, ValueError) as exception:
            raise ValueError("payload must be JSON compatible") from exception
        return hashlib.sha256(encoded).hexdigest()

    @classmethod
    def create(cls, account_key: str, message_id: str, sender_id: str,
               deadline_seconds: float, now: float | None = None,
               payload_digest: str | None = None) -> LoginCommand:
        """创建经过边界校验且带绝对截止时间的登录命令。"""
        checked_account = _identifier(account_key, "account_key")
        checked_message = _identifier(message_id, "message_id")
        checked_sender = _identifier(sender_id, "sender_id")
        created_at = _timestamp(time.time() if now is None else now, "now")
        duration = _positive_number(deadline_seconds, "deadline_seconds")
        digest = (cls.digest({"accountKey": checked_account, "command": "LOGIN",
                              "messageId": checked_message, "senderId": checked_sender})
                  if payload_digest is None else _payload_digest(payload_digest))
        return cls(checked_account, checked_message, checked_sender, digest,
                   created_at, created_at + duration)


@dataclass(frozen=True, slots=True)
class LoginWalEntry:
    """描述 WAL 中可恢复的登录命令状态。"""

    account_key: str
    message_id: str
    sender_id: str
    payload_digest: str
    created_at: float
    deadline_at: float
    phase: str
    task_instance_id: str | None
    attempt: int
    next_attempt_at: float
    last_error_code: str | None
    revision: int
    generation: int


class LoginCommandWal:
    """以受限权限目录持久化登录命令及其终态。"""

    def __init__(self, root: str | os.PathLike[str],
                 clock: Callable[[], float] | None = None, *,
                 max_done_records: int = 10_000,
                 max_dead_records: int = 1_000) -> None:
        """初始化绝对路径 WAL，并创建受限权限的状态目录。"""
        raw_root = os.fspath(root)
        if not isinstance(raw_root, str) or not os.path.isabs(raw_root):
            raise ValueError("WAL root must be an absolute path")
        self.root = Path(raw_root)
        self._clock = time.time if clock is None else clock
        self._max_done_records = _record_limit(max_done_records, "max_done_records")
        self._max_dead_records = _record_limit(max_dead_records, "max_dead_records")
        self._thread_lock = threading.RLock()
        self._prepare_layout()
        with self._locked():
            self._prune_terminal("done", self._max_done_records)
            self._prune_terminal("dead", self._max_dead_records)

    def store(self, command: LoginCommand) -> bool:
        """幂等保存命令；待处理返回真，已有终态墓碑返回假。"""
        if not isinstance(command, LoginCommand):
            raise TypeError("command must be LoginCommand")
        command = _validated_command(command)
        name = _entry_name(command.account_key, command.message_id)
        with self._locked():
            for state in ("pending", "done", "dead"):
                path = self.root / state / name
                if not _lexists(path):
                    continue
                try:
                    existing = self._read_entry(path, state)
                except _InvalidRecord as exception:
                    self._isolate_invalid(path, state, exception.error_code)
                    if state != "pending":
                        self._write_quarantine(name, exception.error_code)
                        return False
                    self._write_quarantine(name, exception.error_code)
                    return False
                self._assert_same_command(command, existing)
                return state == "pending"
            entry = LoginWalEntry(
                account_key=command.account_key,
                message_id=command.message_id,
                sender_id=command.sender_id,
                payload_digest=command.payload_digest,
                created_at=command.created_at,
                deadline_at=command.deadline_at,
                phase=PHASE_EXECUTE,
                task_instance_id=None,
                attempt=0,
                next_attempt_at=command.created_at,
                last_error_code=None,
                revision=0,
                generation=0,
            )
            self._atomic_write(self.root / "pending", name, _entry_document(entry))
            return True

    def load_batch(self, now: float, limit: int) -> list[LoginWalEntry]:
        """加载到期记录，并隔离损坏记录而不中断后续事件。"""
        checked_now = _timestamp(now, "now")
        if isinstance(limit, bool) or not isinstance(limit, int) or limit <= 0:
            raise ValueError("limit must be a positive integer")
        with self._locked():
            entries: list[LoginWalEntry] = []
            pending = self.root / "pending"
            for path in tuple(pending.iterdir()):
                if path.name.startswith(".tmp-"):
                    self._discard_temporary(path)
                    continue
                try:
                    entry = self._read_entry(path, "pending")
                except _InvalidRecord as exception:
                    self._isolate_invalid(path, "pending", exception.error_code)
                    continue
                if entry.next_attempt_at <= checked_now:
                    entries.append(entry)
            entries.sort(key=lambda value: (value.next_attempt_at, value.created_at,
                                             value.account_key, value.message_id))
            return entries[:limit]

    def attach_task(self, entry: LoginWalEntry, task_id: str) -> LoginWalEntry:
        """把调度任务编号原子绑定到执行阶段记录。"""
        checked_task_id = _identifier(task_id, "task_id")
        with self._locked():
            current = self._current(entry)
            if current.phase != PHASE_EXECUTE:
                raise LoginWalStateError("task can only be attached during EXECUTE")
            if current.task_instance_id is not None:
                if current.task_instance_id != checked_task_id:
                    raise LoginWalConflictError("a different task is already attached")
                return current
            updated = replace(current, task_instance_id=checked_task_id,
                              revision=current.revision + 1)
            self._replace_pending(updated)
            return updated

    def reschedule(self, entry: LoginWalEntry, next_at: float,
                   error_code: str) -> LoginWalEntry:
        """记录一次失败并把当前阶段延后到指定时间。"""
        checked_next = _timestamp(next_at, "next_at")
        checked_error = _safe_error_code(error_code)
        with self._locked():
            current = self._current(entry)
            updated = replace(current, attempt=current.attempt + 1,
                              next_attempt_at=checked_next,
                              last_error_code=checked_error,
                              revision=current.revision + 1)
            self._replace_pending(updated)
            return updated

    def begin_failure_reply(self, entry: LoginWalEntry,
                            error_code: str) -> LoginWalEntry:
        """切换到失败回执阶段，并为该阶段重置重试次数。"""
        checked_error = _safe_error_code(error_code)
        with self._locked():
            current = self._current(entry)
            if current.phase == PHASE_FAILURE_REPLY:
                if current.last_error_code != checked_error:
                    raise LoginWalConflictError("failure reply already has another error code")
                return current
            updated = replace(current, phase=PHASE_FAILURE_REPLY, attempt=0,
                              next_attempt_at=_timestamp(self._clock(), "clock"),
                              last_error_code=checked_error,
                              revision=current.revision + 1)
            self._replace_pending(updated)
            return updated

    def complete(self, entry: LoginWalEntry) -> None:
        """把已成功处理的记录原子移动到完成墓碑目录。"""
        with self._locked():
            self._move_terminal(entry, "done", None)

    def mark_dead(self, entry: LoginWalEntry, error_code: str) -> None:
        """把不可继续处理的记录原子移动到死亡墓碑目录。"""
        checked_error = _safe_error_code(error_code)
        with self._locked():
            self._move_terminal(entry, "dead", checked_error)

    def redrive(self, event_key: str, now: float | None = None) -> LoginWalEntry:
        """把精确指定的死亡登录命令恢复为一次新的有界执行。"""
        checked_key = _event_key(event_key)
        redrive_at = _timestamp(self._clock() if now is None else now, "now")
        name = f"{checked_key}.json"
        with self._locked():
            pending = self.root / "pending" / name
            dead = self.root / "dead" / name
            if _lexists(pending):
                try:
                    existing = self._read_entry(pending, "pending")
                except _InvalidRecord as exception:
                    raise LoginWalStateError("pending redrive entry is invalid") from exception
                if _lexists(dead):
                    try:
                        terminal = self._read_entry(dead, "dead")
                    except _InvalidRecord as exception:
                        raise LoginWalStateError(
                            "dead login entry is not redrivable") from exception
                    if (terminal.account_key, terminal.message_id,
                            terminal.sender_id, terminal.payload_digest) != (
                            existing.account_key, existing.message_id,
                            existing.sender_id, existing.payload_digest):
                        raise LoginWalConflictError(
                            "redrive copies have conflicting identity")
                    os.unlink(dead)
                    self._fsync_directory(self.root / "dead")
                return existing
            if not _lexists(dead):
                raise LoginWalStateError("dead login entry does not exist")
            try:
                terminal = self._read_entry(dead, "dead")
            except _InvalidRecord as exception:
                raise LoginWalStateError("dead login entry is not redrivable") from exception
            duration = terminal.deadline_at - terminal.created_at
            restored = replace(
                terminal, created_at=redrive_at, deadline_at=redrive_at + duration,
                phase=PHASE_EXECUTE, task_instance_id=None, attempt=0,
                next_attempt_at=redrive_at, last_error_code=None,
                revision=terminal.revision + 1,
                generation=terminal.generation + 1)
            # 先持久化 pending，再删除 dead；崩溃时最多留下可幂等收敛的双副本。
            self._atomic_write(self.root / "pending", name, _entry_document(restored))
            os.unlink(dead)
            self._fsync_directory(self.root / "dead")
            return restored

    def acknowledge_dead(self, event_key: str) -> None:
        """确认精确指定的死亡登录命令，并保留有界完成墓碑。"""
        checked_key = _event_key(event_key)
        name = f"{checked_key}.json"
        with self._locked():
            dead = self.root / "dead" / name
            done = self.root / "done" / name
            if not _lexists(dead):
                if _lexists(done):
                    self._read_entry(done, "done")
                    return
                raise LoginWalStateError("dead login entry does not exist")
            try:
                terminal = self._read_entry(dead, "dead")
            except _InvalidRecord as exception:
                raise LoginWalStateError("dead login entry is not acknowledgeable") from exception
            if _lexists(done):
                try:
                    completed = self._read_entry(done, "done")
                except _InvalidRecord as exception:
                    raise LoginWalStateError("done login entry is invalid") from exception
                if (terminal.account_key, terminal.message_id,
                        terminal.sender_id, terminal.payload_digest) != (
                        completed.account_key, completed.message_id,
                        completed.sender_id, completed.payload_digest):
                    raise LoginWalConflictError(
                        "acknowledgement tombstones have conflicting identity")
                os.unlink(dead)
                self._fsync_directory(self.root / "dead")
                return
            os.replace(dead, done)
            self._stamp_terminal(done)
            self._fsync_directory(self.root / "dead")
            self._fsync_directory(self.root / "done")
            self._prune_terminal("done", self._max_done_records)

    def stats(self) -> dict[str, int]:
        """返回待处理、已完成和死亡记录数量。"""
        with self._locked():
            self._sweep_invalid()
            return {state: sum(1 for path in (self.root / state).iterdir()
                               if not path.name.startswith(".tmp-"))
                    for state in ("pending", "done", "dead")}

    def record_rejected_event(self, account_key: str, event_type: str,
                              message_id: str, sequence_number: int | None,
                              error_code: str, maximum_records: int) -> None:
        """以不含正文和发送者的安全元数据记录永久拒绝事件。"""
        account = _identifier(account_key, "account_key")
        checked_event_type = _event_type(event_type)
        message_digest = hashlib.sha256(
            str(message_id).encode("utf-8", errors="replace")).hexdigest()
        sequence = _optional_sequence(sequence_number)
        checked_error = _safe_error_code(error_code)
        if isinstance(maximum_records, bool) or not isinstance(maximum_records, int) \
                or maximum_records < 1 or maximum_records > 100_000:
            raise ValueError("maximum_records is invalid")
        identity = f"{account}\0{checked_event_type}\0{message_digest}\0{sequence}"
        event_key = hashlib.sha256(identity.encode("utf-8")).hexdigest()
        name = f"{event_key}.json"
        with self._locked():
            directory = self.root / "rejected"
            target = directory / name
            if _lexists(target):
                try:
                    self._read_rejection(target)
                    return
                except _InvalidRecord:
                    try:
                        os.unlink(target)
                        self._fsync_directory(directory)
                    except OSError as exception:
                        raise LoginWalError(
                            "unable to replace invalid rejection record") from exception
            self._prune_rejections(maximum_records - 1)
            document: dict[str, Any] = {
                "version": _VERSION,
                "kind": "REJECTED_EVENT",
                "eventKey": event_key,
                "accountKey": account,
                "eventType": checked_event_type,
                "messageIdDigest": message_digest,
                "sequenceNumber": sequence,
                "rejectedAt": _timestamp(self._clock(), "clock"),
                "lastErrorCode": checked_error,
            }
            document["recordDigest"] = hashlib.sha256(
                _canonical_bytes(document)).hexdigest()
            self._atomic_write(directory, name, document)

    def rejected_count(self) -> int:
        """校验并返回受容量限制的永久拒绝事件数量。"""
        with self._locked():
            records = []
            for path in tuple((self.root / "rejected").iterdir()):
                if path.name.startswith(".tmp-"):
                    self._discard_temporary(path)
                    continue
                records.append(path)
            for path in records:
                self._read_rejection(path)
            return len(records)

    def _prepare_layout(self) -> None:
        if not _lexists(self.root.parent):
            raise LoginWalSecurityError("WAL root parent must already exist")
        try:
            self.root.mkdir(mode=0o700, exist_ok=True)
        except OSError as exception:
            raise LoginWalSecurityError("unable to create WAL root") from exception
        self._secure_directory(self.root)
        # 每次初始化都同步父目录，以覆盖上次进程在 mkdir 与 fsync 之间中断的场景。
        self._fsync_directory(self.root.parent)
        for state in ("pending", "done", "dead", "rejected"):
            directory = self.root / state
            try:
                directory.mkdir(mode=0o700, exist_ok=True)
            except OSError as exception:
                raise LoginWalSecurityError("unable to create WAL state directory") from exception
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
                    raise LoginWalSecurityError("WAL lock must be a regular file")
                os.fchmod(descriptor, 0o600)
            finally:
                os.close(descriptor)
        except LoginWalSecurityError:
            raise
        except OSError as exception:
            raise LoginWalSecurityError("invalid WAL lock file") from exception
        # 每次都同步完整命名空间，覆盖子目录创建完成但根目录 fsync 前中断的场景。
        self._fsync_directory(self.root)

    def _verify_layout(self) -> None:
        self._secure_directory(self.root)
        for state in ("pending", "done", "dead", "rejected"):
            self._secure_directory(self.root / state)

    @staticmethod
    def _secure_directory(path: Path) -> None:
        try:
            metadata = os.lstat(path)
            if stat.S_ISLNK(metadata.st_mode):
                raise LoginWalSecurityError("WAL directory must not be a symlink")
            if not stat.S_ISDIR(metadata.st_mode):
                raise LoginWalSecurityError("WAL path must be a directory")
            flags = os.O_RDONLY
            if hasattr(os, "O_DIRECTORY"):
                flags |= os.O_DIRECTORY
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            descriptor = os.open(path, flags)
            try:
                opened = os.fstat(descriptor)
                if (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino):
                    raise LoginWalSecurityError("WAL directory changed during validation")
                os.fchmod(descriptor, 0o700)
            finally:
                os.close(descriptor)
        except LoginWalSecurityError:
            raise
        except OSError as exception:
            raise LoginWalSecurityError("invalid WAL directory") from exception

    @contextmanager
    def _locked(self) -> Iterator[None]:
        with self._thread_lock:
            self._verify_layout()
            lock_path = self.root / ".lock"
            flags = os.O_RDWR
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            try:
                descriptor = os.open(lock_path, flags)
            except OSError as exception:
                raise LoginWalSecurityError("invalid WAL lock file") from exception
            try:
                metadata = os.fstat(descriptor)
                if not stat.S_ISREG(metadata.st_mode):
                    raise LoginWalSecurityError("WAL lock must be a regular file")
                os.fchmod(descriptor, 0o600)
                fcntl.flock(descriptor, fcntl.LOCK_EX)
                yield
            finally:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
                os.close(descriptor)

    def _read_entry(self, path: Path, _state: str) -> LoginWalEntry:
        document = self._read_document(path)
        if document.get("kind") == "QUARANTINE":
            self._validate_quarantine(document, path)
            raise _InvalidRecord("WAL_QUARANTINED")
        if frozenset(document) not in {_ENTRY_FIELDS, _ENTRY_FIELDS_V2} \
                or document.get("kind") != "LOGIN_COMMAND":
            raise _InvalidRecord("WAL_SCHEMA")
        if not self._valid_record_digest(document):
            raise _InvalidRecord("WAL_INTEGRITY")
        try:
            entry = LoginWalEntry(
                account_key=_identifier(document["accountKey"], "accountKey"),
                message_id=_identifier(document["messageId"], "messageId"),
                sender_id=_identifier(document["senderId"], "senderId"),
                payload_digest=_payload_digest(document["payloadDigest"]),
                created_at=_timestamp(document["createdAt"], "createdAt"),
                deadline_at=_timestamp(document["deadlineAt"], "deadlineAt"),
                phase=_phase(document["phase"]),
                task_instance_id=_optional_identifier(document["taskInstanceId"],
                                                       "taskInstanceId"),
                attempt=_nonnegative_integer(document["attempt"], "attempt"),
                next_attempt_at=_timestamp(document["nextAttemptAt"], "nextAttemptAt"),
                last_error_code=_optional_error_code(document["lastErrorCode"]),
                revision=_nonnegative_integer(document["revision"], "revision"),
                generation=_nonnegative_integer(document.get("generation", 0), "generation"),
            )
        except (TypeError, ValueError) as exception:
            raise _InvalidRecord("WAL_SCHEMA") from exception
        if document.get("version") != _VERSION or entry.deadline_at < entry.created_at:
            raise _InvalidRecord("WAL_SCHEMA")
        if path.name != _entry_name(entry.account_key, entry.message_id):
            raise _InvalidRecord("WAL_INTEGRITY")
        return entry

    def _read_rejection(self, path: Path) -> None:
        document = self._read_document(path)
        if frozenset(document) != _REJECTION_FIELDS \
                or document.get("version") != _VERSION \
                or document.get("kind") != "REJECTED_EVENT" \
                or not self._valid_record_digest(document) \
                or document.get("eventKey") != path.stem:
            raise _InvalidRecord("WAL_INTEGRITY")
        try:
            _identifier(document["accountKey"], "accountKey")
            _event_type(document["eventType"])
            _payload_digest(document["messageIdDigest"])
            _optional_sequence(document["sequenceNumber"])
            _timestamp(document["rejectedAt"], "rejectedAt")
            _safe_error_code(document["lastErrorCode"])
        except (TypeError, ValueError) as exception:
            raise _InvalidRecord("WAL_SCHEMA") from exception

    def _prune_rejections(self, keep: int) -> None:
        directory = self.root / "rejected"
        try:
            records = sorted(
                directory.iterdir(), key=lambda path: (path.stat().st_mtime_ns, path.name))
            for path in records[:max(0, len(records) - keep)]:
                os.unlink(path)
            if len(records) > keep:
                self._fsync_directory(directory)
        except OSError as exception:
            raise LoginWalError("unable to prune rejection record") from exception

    def _read_document(self, path: Path) -> dict[str, Any]:
        try:
            metadata = os.lstat(path)
            if stat.S_ISLNK(metadata.st_mode):
                raise _InvalidRecord("WAL_SYMLINK")
            if not stat.S_ISREG(metadata.st_mode):
                raise _InvalidRecord("WAL_FILE_TYPE")
            if stat.S_IMODE(metadata.st_mode) != 0o600:
                raise _InvalidRecord("WAL_PERMISSION")
            if metadata.st_size > _MAX_RECORD_BYTES:
                raise _InvalidRecord("WAL_TOO_LARGE")
            flags = os.O_RDONLY
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            descriptor = os.open(path, flags)
            try:
                opened = os.fstat(descriptor)
                if (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino):
                    raise _InvalidRecord("WAL_INTEGRITY")
                chunks: list[bytes] = []
                remaining = _MAX_RECORD_BYTES + 1
                while remaining:
                    chunk = os.read(descriptor, min(remaining, 8192))
                    if not chunk:
                        break
                    chunks.append(chunk)
                    remaining -= len(chunk)
                raw = b"".join(chunks)
                if len(raw) > _MAX_RECORD_BYTES:
                    raise _InvalidRecord("WAL_TOO_LARGE")
            finally:
                os.close(descriptor)
            parsed = json.loads(raw.decode("utf-8"), parse_constant=_reject_json_constant)
            if not isinstance(parsed, dict):
                raise _InvalidRecord("WAL_JSON")
            return parsed
        except _InvalidRecord:
            raise
        except (OSError, UnicodeDecodeError, ValueError, RecursionError) as exception:
            code = "WAL_PERMISSION" if isinstance(exception, PermissionError) \
                else "WAL_IO" if isinstance(exception, OSError) else "WAL_JSON"
            raise _InvalidRecord(code) from exception

    @staticmethod
    def _valid_record_digest(document: Mapping[str, Any]) -> bool:
        digest = document.get("recordDigest")
        if not isinstance(digest, str) or _DIGEST.fullmatch(digest) is None:
            return False
        unsigned = dict(document)
        del unsigned["recordDigest"]
        try:
            actual = hashlib.sha256(_canonical_bytes(unsigned)).hexdigest()
        except (TypeError, ValueError, RecursionError, OverflowError):
            return False
        return actual == digest

    def _validate_quarantine(self, document: dict[str, Any], path: Path) -> None:
        if frozenset(document) != _QUARANTINE_FIELDS \
                or document.get("version") != _VERSION \
                or not self._valid_record_digest(document) \
                or document.get("eventKey") != path.stem:
            raise _InvalidRecord("WAL_INTEGRITY")
        try:
            _timestamp(document["quarantinedAt"], "quarantinedAt")
            _safe_error_code(document["lastErrorCode"])
        except (TypeError, ValueError) as exception:
            raise _InvalidRecord("WAL_SCHEMA") from exception

    @staticmethod
    def _assert_same_command(command: LoginCommand, entry: LoginWalEntry) -> None:
        if command.account_key != entry.account_key or command.message_id != entry.message_id:
            raise LoginWalConflictError("idempotency key does not match stored command")
        if command.sender_id != entry.sender_id or command.payload_digest != entry.payload_digest:
            raise LoginWalConflictError("idempotency key has conflicting sender or payload")

    def _current(self, expected: LoginWalEntry) -> LoginWalEntry:
        if not isinstance(expected, LoginWalEntry):
            raise TypeError("entry must be LoginWalEntry")
        name = _entry_name(expected.account_key, expected.message_id)
        path = self.root / "pending" / name
        if not _lexists(path):
            raise LoginWalStateError("pending entry does not exist")
        try:
            current = self._read_entry(path, "pending")
        except _InvalidRecord as exception:
            self._isolate_invalid(path, "pending", exception.error_code)
            raise LoginWalStateError("pending entry is invalid") from exception
        if current != expected:
            raise LoginWalStateError("entry revision is stale")
        return current

    def _replace_pending(self, entry: LoginWalEntry) -> None:
        self._atomic_write(self.root / "pending",
                           _entry_name(entry.account_key, entry.message_id),
                           _entry_document(entry))

    def _move_terminal(self, expected: LoginWalEntry, target_state: str,
                       error_code: str | None) -> None:
        name = _entry_name(expected.account_key, expected.message_id)
        source = self.root / "pending" / name
        target = self.root / target_state / name
        if not _lexists(source):
            if _lexists(target):
                try:
                    terminal = self._read_entry(target, target_state)
                except _InvalidRecord as exception:
                    self._isolate_invalid(target, target_state, exception.error_code)
                    raise LoginWalStateError("terminal tombstone is invalid") from exception
                if terminal.account_key != expected.account_key \
                        or terminal.message_id != expected.message_id \
                        or terminal.sender_id != expected.sender_id \
                        or terminal.payload_digest != expected.payload_digest:
                    raise LoginWalConflictError("terminal tombstone conflicts with entry")
                if error_code is not None and terminal.last_error_code != error_code:
                    raise LoginWalConflictError("terminal error code conflicts with entry")
                return
            raise LoginWalStateError("pending entry does not exist")
        current = self._current(expected)
        if error_code is not None:
            current = replace(current, last_error_code=error_code,
                              revision=current.revision + 1)
            self._replace_pending(current)
        if _lexists(target):
            try:
                existing = self._read_entry(target, target_state)
            except _InvalidRecord as exception:
                self._isolate_invalid(target, target_state, exception.error_code)
            else:
                if existing.account_key != current.account_key \
                        or existing.message_id != current.message_id \
                        or existing.sender_id != current.sender_id \
                        or existing.payload_digest != current.payload_digest:
                    raise LoginWalConflictError("terminal tombstone already exists")
        try:
            os.replace(source, target)
            self._stamp_terminal(target)
            self._fsync_directory(self.root / "pending")
            self._fsync_directory(self.root / target_state)
            limit = self._max_done_records if target_state == "done" \
                else self._max_dead_records
            self._prune_terminal(target_state, limit)
        except OSError as exception:
            raise LoginWalError("unable to move WAL entry to terminal state") from exception

    def _isolate_invalid(self, path: Path, source_state: str, error_code: str) -> None:
        safe_error = _safe_error_code(error_code)
        event_key = path.stem if _DIGEST.fullmatch(path.stem) else hashlib.sha256(
            path.name.encode("utf-8", errors="replace")).hexdigest()
        target_name = f"{event_key}.json"
        target = self.root / "dead" / target_name
        if source_state == "dead" and path == target:
            self._write_quarantine(target_name, safe_error)
            return
        if not _lexists(target):
            self._write_quarantine(target_name, safe_error)
        try:
            if path.is_symlink() or not path.is_dir():
                os.unlink(path)
                self._fsync_directory(path.parent)
        except FileNotFoundError:
            return
        except OSError as exception:
            raise LoginWalSecurityError("unable to isolate invalid WAL record") from exception

    def _write_quarantine(self, name: str, error_code: str) -> None:
        event_key = Path(name).stem
        document: dict[str, Any] = {
            "version": _VERSION,
            "kind": "QUARANTINE",
            "eventKey": event_key,
            "quarantinedAt": _timestamp(self._clock(), "clock"),
            "lastErrorCode": _safe_error_code(error_code),
        }
        document["recordDigest"] = hashlib.sha256(_canonical_bytes(document)).hexdigest()
        self._atomic_write(self.root / "dead", f"{event_key}.json", document)
        self._stamp_terminal(self.root / "dead" / f"{event_key}.json")
        self._prune_terminal("dead", self._max_dead_records)

    def _stamp_terminal(self, path: Path) -> None:
        directory = path.parent
        latest = max((item.stat().st_mtime_ns for item in directory.iterdir()
                      if item != path and not item.name.startswith(".tmp-")), default=-1)
        stamp = max(int(_timestamp(self._clock(), "clock") * 1_000_000_000), latest + 1)
        os.utime(path, ns=(stamp, stamp), follow_symlinks=False)

    def _prune_terminal(self, state: str, keep: int) -> None:
        directory = self.root / state
        try:
            records = sorted((path for path in directory.iterdir()
                              if not path.name.startswith(".tmp-")),
                             key=lambda path: (path.stat().st_mtime_ns, path.name))
            for path in records[:max(0, len(records) - keep)]:
                os.unlink(path)
            if len(records) > keep:
                self._fsync_directory(directory)
        except OSError as exception:
            raise LoginWalError("unable to enforce terminal retention") from exception

    def _sweep_invalid(self) -> None:
        for state in ("pending", "done", "dead"):
            directory = self.root / state
            for path in tuple(directory.iterdir()):
                if path.name.startswith(".tmp-"):
                    self._discard_temporary(path)
                    continue
                try:
                    document = self._read_document(path)
                    if document.get("kind") == "QUARANTINE":
                        if state != "dead":
                            raise _InvalidRecord("WAL_SCHEMA")
                        self._validate_quarantine(document, path)
                    else:
                        self._read_entry(path, state)
                except _InvalidRecord as exception:
                    self._isolate_invalid(path, state, exception.error_code)

    @staticmethod
    def _discard_temporary(path: Path) -> None:
        try:
            metadata = os.lstat(path)
            if stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
                os.unlink(path)
        except FileNotFoundError:
            return

    def _atomic_write(self, directory: Path, name: str,
                      document: Mapping[str, Any]) -> None:
        data = _canonical_bytes(document) + b"\n"
        descriptor = -1
        temporary = ""
        try:
            descriptor, temporary = tempfile.mkstemp(prefix=".tmp-", dir=directory)
            os.fchmod(descriptor, 0o600)
            offset = 0
            while offset < len(data):
                offset += os.write(descriptor, data[offset:])
            os.fsync(descriptor)
            os.close(descriptor)
            descriptor = -1
            os.replace(temporary, directory / name)
            temporary = ""
            self._fsync_directory(directory)
        except OSError as exception:
            raise LoginWalError("unable to persist WAL record") from exception
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
                raise LoginWalSecurityError("WAL fsync target must be a directory")
            descriptor = os.open(directory, flags)
            try:
                opened = os.fstat(descriptor)
                if not stat.S_ISDIR(opened.st_mode) \
                        or (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino):
                    raise LoginWalSecurityError(
                        "WAL fsync directory changed during validation")
                try:
                    os.fsync(descriptor)
                except OSError as exception:
                    if exception.errno not in {errno.EINVAL, errno.ENOTSUP}:
                        raise
            finally:
                os.close(descriptor)
        except LoginWalSecurityError:
            raise
        except OSError as exception:
            raise LoginWalSecurityError("invalid WAL fsync directory") from exception


def _validated_command(command: LoginCommand) -> LoginCommand:
    account = _identifier(command.account_key, "account_key")
    message = _identifier(command.message_id, "message_id")
    sender = _identifier(command.sender_id, "sender_id")
    digest = _payload_digest(command.payload_digest)
    created = _timestamp(command.created_at, "created_at")
    deadline = _timestamp(command.deadline_at, "deadline_at")
    if deadline < created:
        raise ValueError("deadline_at must not precede created_at")
    return LoginCommand(account, message, sender, digest, created, deadline)


def _entry_document(entry: LoginWalEntry) -> dict[str, Any]:
    document: dict[str, Any] = {
        "version": _VERSION,
        "kind": "LOGIN_COMMAND",
        "accountKey": entry.account_key,
        "messageId": entry.message_id,
        "senderId": entry.sender_id,
        "payloadDigest": entry.payload_digest,
        "createdAt": entry.created_at,
        "deadlineAt": entry.deadline_at,
        "phase": entry.phase,
        "taskInstanceId": entry.task_instance_id,
        "attempt": entry.attempt,
        "nextAttemptAt": entry.next_attempt_at,
        "lastErrorCode": entry.last_error_code,
        "revision": entry.revision,
        "generation": entry.generation,
    }
    document["recordDigest"] = hashlib.sha256(_canonical_bytes(document)).hexdigest()
    return document


def _canonical_bytes(document: Mapping[str, Any]) -> bytes:
    return json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      allow_nan=False).encode("utf-8")


def _entry_name(account_key: str, message_id: str) -> str:
    value = f"{account_key}\x00{message_id}".encode("utf-8")
    return f"{hashlib.sha256(value).hexdigest()}.json"


def _identifier(value: object, name: str) -> str:
    if not isinstance(value, str) or not value or value != value.strip() \
            or len(value) > _MAX_IDENTIFIER_LENGTH \
            or any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise ValueError(f"{name} must be a non-blank bounded string")
    return value


def _optional_identifier(value: object, name: str) -> str | None:
    return None if value is None else _identifier(value, name)


def _payload_digest(value: object) -> str:
    if not isinstance(value, str):
        raise TypeError("payload_digest must be a string")
    normalized = value.lower()
    if _DIGEST.fullmatch(normalized) is None:
        raise ValueError("payload_digest must be a SHA-256 hexadecimal digest")
    return normalized


def _event_key(value: object) -> str:
    if not isinstance(value, str) or _DIGEST.fullmatch(value) is None:
        raise ValueError("event_key must be a lowercase SHA-256 hexadecimal digest")
    return value


def _record_limit(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) \
            or value < 1 or value > 1_000_000:
        raise ValueError(f"{name} must be a positive bounded integer")
    return value


def _phase(value: object) -> str:
    if not isinstance(value, str) or value not in _PHASES:
        raise ValueError("phase is invalid")
    return value


def _event_type(value: object) -> str:
    if not isinstance(value, str) or _EVENT_TYPE.fullmatch(value) is None:
        raise ValueError("event_type is invalid")
    return value


def _optional_sequence(value: object) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("sequence_number is invalid")
    return value


def _safe_error_code(value: object) -> str:
    if not isinstance(value, str):
        raise TypeError("error_code must be a string")
    if _ERROR_CODE.fullmatch(value) is None:
        raise ValueError("error_code contains unsafe characters or is too long")
    return value


def _optional_error_code(value: object) -> str | None:
    return None if value is None else _safe_error_code(value)


def _timestamp(value: object, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError(f"{name} must be a number")
    result = float(value)
    if not math.isfinite(result) or result < 0:
        raise ValueError(f"{name} must be finite and non-negative")
    return result


def _positive_number(value: object, name: str) -> float:
    result = _timestamp(value, name)
    if result <= 0:
        raise ValueError(f"{name} must be positive")
    return result


def _nonnegative_integer(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{name} must be a non-negative integer")
    return value


def _lexists(path: Path) -> bool:
    return os.path.lexists(path)


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"invalid JSON constant: {value}")
