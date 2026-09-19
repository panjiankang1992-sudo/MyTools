"""OneBot 入站事件的本机原子预写日志。"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from hashlib import sha256
import json
import logging
import os
from pathlib import Path
import stat
import tempfile
import threading
import time
from typing import Any

from .models import InboundEvent, MAXIMUM_INBOUND_PAYLOAD_BYTES

logger = logging.getLogger(__name__)
WAL_FORMAT_VERSION = 1
MAXIMUM_WAL_BYTES = MAXIMUM_INBOUND_PAYLOAD_BYTES + 4096
DEFAULT_MAX_PENDING_RECORDS = 10_000
DEFAULT_MAX_TOTAL_PAYLOAD_BYTES = 128 * 1024 * 1024
DEFAULT_MAX_DEAD_RECORDS = 1_000
MAXIMUM_PENDING_RECORDS_LIMIT = 1_000_000
MAXIMUM_TOTAL_PAYLOAD_BYTES_LIMIT = 4 * 1024 * 1024 * 1024
MAXIMUM_DEAD_RECORDS_LIMIT = 100_000
REJECTION_FORMAT_VERSION = 1
MAXIMUM_REJECTION_BYTES = 1024


class InboundWalCapacityError(RuntimeError):
    """表示 pending 数量或原始载荷达到本机硬上限。"""


@dataclass(frozen=True, slots=True)
class InboundWalEntry:
    """包含数据库导入退避状态的一条 WAL 记录。"""

    event: InboundEvent
    attempt_count: int
    next_attempt_at: datetime
    last_error_code: str | None


class InboundEventWal:
    """使用同目录临时文件、fsync 和原子替换保存当前渠道帧。"""

    def __init__(self, root: str | Path, *,
                 max_pending_records: int = DEFAULT_MAX_PENDING_RECORDS,
                 max_total_payload_bytes: int = DEFAULT_MAX_TOTAL_PAYLOAD_BYTES,
                 max_dead_records: int = DEFAULT_MAX_DEAD_RECORDS) -> None:
        """初始化 WAL，并在接收新帧前恢复有界容量索引。"""
        candidate = Path(root)
        if not candidate.is_absolute():
            raise ValueError("OneBot inbound WAL path must be absolute")
        self._root = candidate
        self._dead = self._root / "dead"
        self._max_pending_records = self._positive_limit(
            max_pending_records, "max_pending_records", MAXIMUM_PENDING_RECORDS_LIMIT)
        self._max_total_payload_bytes = self._positive_limit(
            max_total_payload_bytes, "max_total_payload_bytes",
            MAXIMUM_TOTAL_PAYLOAD_BYTES_LIMIT)
        self._max_dead_records = self._positive_limit(
            max_dead_records, "max_dead_records", MAXIMUM_DEAD_RECORDS_LIMIT)
        self._lock = threading.RLock()
        self._pending_payload_bytes: dict[str, int] = {}
        self._pending_total_payload_bytes = 0
        self._root_signature: tuple[int, int] | None = None
        self._dead_signature: tuple[int, int] | None = None
        self._prepare_layout()
        with self._lock:
            self._rebuild_index()

    def store(self, event: InboundEvent) -> InboundEvent:
        """在返回前把事件完整写入稳定存储。"""
        if not isinstance(event, InboundEvent):
            raise TypeError("OneBot inbound WAL event is invalid")
        target = self._path(event)
        payload_bytes = len(event.payload_json.encode("utf-8"))
        with self._lock:
            self._ensure_index_current()
            if target.name in self._pending_payload_bytes:
                existing = self._read(target).event
                if not existing.matches(event):
                    raise ValueError("OneBot inbound WAL idempotency conflict")
                return existing
            if len(self._pending_payload_bytes) >= self._max_pending_records:
                raise InboundWalCapacityError("OneBot inbound WAL pending record limit exceeded")
            if self._pending_total_payload_bytes + payload_bytes \
                    > self._max_total_payload_bytes:
                raise InboundWalCapacityError("OneBot inbound WAL payload capacity exceeded")
            entry = InboundWalEntry(event, 0, event.created_at, None)
            try:
                self._write(target, self._envelope(entry))
                self._pending_payload_bytes[target.name] = payload_bytes
                self._pending_total_payload_bytes += payload_bytes
                self._capture_signatures()
            except Exception:
                # 写入结果不明确时，下次操作必须完全按磁盘恢复索引。
                self._root_signature = None
                raise
            return event

    def load_batch(self, now: datetime, limit: int) -> list[InboundWalEntry]:
        """按到期时间读取一批记录，并隔离无法解析的坏文件。"""
        if limit <= 0:
            raise ValueError("OneBot inbound WAL batch limit is invalid")
        with self._lock:
            self._ensure_index_current()
            entries: list[tuple[Path, InboundWalEntry]] = []
            for name in tuple(self._pending_payload_bytes):
                path = self._root / name
                try:
                    entry = self._read(path)
                    if entry.next_attempt_at <= now:
                        entries.append((path, entry))
                except (OSError, TypeError, ValueError, json.JSONDecodeError) as exception:
                    self._quarantine(path, "INVALID_WAL")
                    logger.error(
                        "OneBot inbound WAL quarantined errorCode=%s errorType=%s",
                        "INVALID_WAL", type(exception).__name__)
            entries.sort(key=lambda item: (item[1].next_attempt_at,
                                           item[1].event.created_at, item[0].name))
            return [entry for _, entry in entries[:limit]]

    def reschedule(self, entry: InboundWalEntry, next_attempt_at: datetime,
                   error_code: str) -> InboundWalEntry:
        """原子持久化一次数据库导入失败及下次时间。"""
        with self._lock:
            self._ensure_index_current()
            target = self._path(entry.event)
            current = self._read(target)
            if not current.event.matches(entry.event) \
                    or current.attempt_count != entry.attempt_count:
                raise RuntimeError("OneBot inbound WAL attempt is stale")
            updated = InboundWalEntry(
                entry.event, entry.attempt_count + 1, next_attempt_at, error_code[:128])
            try:
                self._write(target, self._envelope(updated))
                self._capture_signatures()
            except Exception:
                self._root_signature = None
                raise
            return updated

    def _write(self, target: Path, envelope: dict[str, Any]) -> None:
        content = json.dumps(
            envelope, allow_nan=False, ensure_ascii=False,
            separators=(",", ":"), sort_keys=True).encode("utf-8")
        if len(content) > MAXIMUM_WAL_BYTES:
            raise ValueError("OneBot inbound WAL record is too large")
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{target.stem}.", suffix=".tmp", dir=self._root)
        temporary = Path(temporary_name)
        try:
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                descriptor = -1
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, target)
            self._fsync_directory(self._root)
        finally:
            if descriptor >= 0:
                os.close(descriptor)
            temporary.unlink(missing_ok=True)

    def acknowledge(self, event: InboundEvent) -> None:
        """数据库提交成功后删除对应 WAL 并同步目录元数据。"""
        with self._lock:
            self._ensure_index_current()
            target = self._path(event)
            if target.name in self._pending_payload_bytes:
                current = self._read(target)
                if not current.event.matches(event):
                    raise ValueError("OneBot inbound WAL idempotency conflict")
            target.unlink(missing_ok=True)
            self._fsync_directory(self._root)
            removed_bytes = self._pending_payload_bytes.pop(target.name, 0)
            self._pending_total_payload_bytes -= removed_bytes
            self._capture_signatures()

    def mark_dead(self, event: InboundEvent, error_code: str) -> None:
        """把确定性坏数据移入不会自动重试的隔离目录。"""
        with self._lock:
            self._ensure_index_current()
            target = self._path(event)
            if target.name in self._pending_payload_bytes:
                current = self._read(target)
                if not current.event.matches(event):
                    raise ValueError("OneBot inbound WAL idempotency conflict")
                self._quarantine(target, error_code)

    def pending_count(self) -> int:
        """返回尚未导入数据库的有界文件数量。"""
        with self._lock:
            self._ensure_index_current()
            return len(self._pending_payload_bytes)

    def pending_payload_bytes(self) -> int:
        """返回 pending 原始载荷总字节数，不读取或暴露内容。"""
        with self._lock:
            self._ensure_index_current()
            return self._pending_total_payload_bytes

    def dead_count(self) -> int:
        """返回不会自动重投的有界 dead 记录数。"""
        with self._lock:
            self._ensure_index_current()
            return len(self._dead_records())

    def record_rejection(self, error_code: str, payload_digest: str,
                         payload_length: int) -> None:
        """持久保存不含渠道原文和标识符的有界拒绝墓碑。"""
        safe_code = self._safe_error_suffix(error_code)
        if safe_code != error_code or len(payload_digest) != 64 \
                or any(character not in "0123456789abcdef" for character in payload_digest) \
                or isinstance(payload_length, bool) or not isinstance(payload_length, int) \
                or payload_length < -1:
            raise ValueError("OneBot inbound rejection metadata is invalid")
        created_at = time.time_ns()
        envelope = {
            "version": REJECTION_FORMAT_VERSION,
            "errorCode": safe_code,
            "payloadDigest": payload_digest,
            "payloadLength": payload_length,
        }
        content = json.dumps(
            envelope, allow_nan=False, ensure_ascii=True,
            separators=(",", ":"), sort_keys=True).encode("ascii")
        if len(content) > MAXIMUM_REJECTION_BYTES:
            raise ValueError("OneBot inbound rejection record is too large")
        with self._lock:
            self._ensure_index_current()
            name_digest = sha256(
                f"{payload_digest}\0{safe_code}\0{created_at}".encode("ascii")
            ).hexdigest()
            target = self._dead / f"{name_digest}.{safe_code}.rejected.dead"
            descriptor, temporary_name = tempfile.mkstemp(
                prefix=f".{name_digest}.", suffix=".tmp", dir=self._dead)
            temporary = Path(temporary_name)
            try:
                os.fchmod(descriptor, 0o600)
                with os.fdopen(descriptor, "wb") as stream:
                    descriptor = -1
                    stream.write(content)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, target)
                os.utime(target, ns=(created_at, created_at), follow_symlinks=False)
                self._fsync_directory(self._dead)
                self._prune_dead()
                self._capture_signatures()
            finally:
                if descriptor >= 0:
                    os.close(descriptor)
                temporary.unlink(missing_ok=True)

    def rejected_count(self) -> int:
        """返回持久拒绝墓碑数量，不读取其中的任何渠道数据。"""
        with self._lock:
            self._ensure_index_current()
            return sum(path.name.endswith(".rejected.dead")
                       for _, _, path in self._dead_records())

    def _read(self, path: Path) -> InboundWalEntry:
        if path.parent.resolve(strict=True) != self._root:
            raise ValueError("OneBot inbound WAL file escaped its root")
        file_stat = path.stat(follow_symlinks=False)
        if not stat.S_ISREG(file_stat.st_mode) or stat.S_IMODE(file_stat.st_mode) != 0o600 \
                or file_stat.st_size > MAXIMUM_WAL_BYTES:
            raise ValueError("OneBot inbound WAL file metadata is invalid")
        value: Any = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or value.get("version") != WAL_FORMAT_VERSION \
                or not isinstance(value.get("payload"), dict):
            raise ValueError("OneBot inbound WAL record is invalid")
        created_at = datetime.fromisoformat(str(value.get("createdAt") or ""))
        next_attempt_at = datetime.fromisoformat(str(value.get("nextAttemptAt") or ""))
        attempt_count = value.get("importAttemptCount")
        if created_at.tzinfo is None or next_attempt_at.tzinfo is None \
                or not isinstance(attempt_count, int) or attempt_count < 0:
            raise ValueError("OneBot inbound WAL timestamp is invalid")
        event = InboundEvent.create(
            str(value.get("accountKey") or ""), str(value.get("eventKey") or ""),
            str(value.get("sourceMessageId") or ""), value["payload"], created_at)
        if value.get("payloadDigest") != event.payload_digest or path != self._path(event):
            raise ValueError("OneBot inbound WAL digest is invalid")
        error_code = value.get("lastErrorCode")
        if error_code is not None and not isinstance(error_code, str):
            raise ValueError("OneBot inbound WAL error code is invalid")
        return InboundWalEntry(event, attempt_count, next_attempt_at, error_code)

    @staticmethod
    def _envelope(entry: InboundWalEntry) -> dict[str, Any]:
        event = entry.event
        return {
            "version": WAL_FORMAT_VERSION,
            "accountKey": event.account_key,
            "eventKey": event.event_key,
            "sourceMessageId": event.source_message_id,
            "payloadDigest": event.payload_digest,
            "createdAt": event.created_at.isoformat(),
            "importAttemptCount": entry.attempt_count,
            "nextAttemptAt": entry.next_attempt_at.isoformat(),
            "lastErrorCode": entry.last_error_code,
            "payload": event.payload(),
        }

    def _path(self, event: InboundEvent) -> Path:
        identity = f"{event.account_key}\0{event.event_key}".encode("utf-8")
        return self._root / f"{sha256(identity).hexdigest()}.json"

    def _quarantine(self, path: Path, error_code: str) -> None:
        metadata = path.stat(follow_symlinks=False)
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError("OneBot inbound WAL source file metadata is invalid")
        self._root_signature = None
        self._dead_signature = None
        suffix = self._safe_error_suffix(error_code)
        private_name = sha256(path.name.encode("utf-8")).hexdigest()
        target = self._dead / f"{private_name}.{suffix}.dead"
        if os.path.lexists(target):
            target = self._dead / (
                f"{private_name}.{suffix}.{os.getpid()}.{metadata.st_mtime_ns}.dead")
        if os.path.lexists(target):
            raise ValueError("OneBot inbound WAL dead target already exists")
        newest_dead_at = max((item[0] for item in self._dead_records()), default=0)
        terminal_at = max(time.time_ns(), newest_dead_at + 1)
        os.replace(path, target)
        target_metadata = target.stat(follow_symlinks=False)
        if not stat.S_ISREG(target_metadata.st_mode):
            raise ValueError("OneBot inbound WAL dead target metadata is invalid")
        os.chmod(target, 0o600, follow_symlinks=False)
        os.utime(target, ns=(terminal_at, terminal_at), follow_symlinks=False)
        self._fsync_directory(self._root)
        self._fsync_directory(self._dead)
        removed_bytes = self._pending_payload_bytes.pop(path.name, 0)
        self._pending_total_payload_bytes -= removed_bytes
        self._prune_dead()
        self._capture_signatures()

    def _prepare_layout(self) -> None:
        """按父到子的顺序创建、验证并持久化 WAL 命名空间。"""
        parent = self._root.parent
        self._secure_directory(parent, adjust_mode=False)
        self._mkdir_if_absent(self._root)
        self._secure_directory(self._root, adjust_mode=True)
        # 每次都同步，覆盖上个进程在 mkdir 与父目录 fsync 之间退出的恢复窗口。
        self._fsync_directory(parent)
        self._mkdir_if_absent(self._dead)
        self._secure_directory(self._dead, adjust_mode=True)
        self._fsync_directory(self._root)

    @staticmethod
    def _mkdir_if_absent(path: Path) -> None:
        """只创建直接子目录，已存在时交由描述符验证其类型。"""
        try:
            os.mkdir(path, 0o700)
        except FileExistsError:
            return
        except OSError as exception:
            raise ValueError("OneBot inbound WAL directory could not be created") from exception

    @staticmethod
    def _secure_directory(path: Path, *, adjust_mode: bool) -> None:
        """使用描述符复核目录未在 lstat 与 open 之间被替换。"""
        try:
            metadata = os.lstat(path)
            if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
                raise ValueError("OneBot inbound WAL directory is invalid")
            flags = os.O_RDONLY
            if hasattr(os, "O_DIRECTORY"):
                flags |= os.O_DIRECTORY
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            descriptor = os.open(path, flags)
            try:
                opened = os.fstat(descriptor)
                if (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino):
                    raise ValueError("OneBot inbound WAL directory changed during validation")
                if adjust_mode:
                    os.fchmod(descriptor, 0o700)
            finally:
                os.close(descriptor)
        except ValueError:
            raise
        except OSError as exception:
            raise ValueError("OneBot inbound WAL directory is invalid") from exception

    def _rebuild_index(self) -> None:
        """从磁盘重建容量索引，并把坏 pending 隔离后有界清理 dead。"""
        self._discard_temporaries()
        pending: dict[str, int] = {}
        for path in tuple(self._root.glob("*.json")):
            metadata = path.stat(follow_symlinks=False)
            if not stat.S_ISREG(metadata.st_mode):
                raise ValueError("OneBot inbound WAL pending file metadata is invalid")
            try:
                entry = self._read(path)
            except (OSError, TypeError, ValueError, json.JSONDecodeError) as exception:
                self._pending_payload_bytes = pending
                self._pending_total_payload_bytes = sum(pending.values())
                self._quarantine(path, "INVALID_WAL")
                logger.error(
                    "OneBot inbound WAL quarantined errorCode=%s errorType=%s",
                    "INVALID_WAL", type(exception).__name__)
                continue
            pending[path.name] = len(entry.event.payload_json.encode("utf-8"))
        self._pending_payload_bytes = pending
        self._pending_total_payload_bytes = sum(pending.values())
        self._prune_dead()
        if len(pending) > self._max_pending_records \
                or self._pending_total_payload_bytes > self._max_total_payload_bytes:
            raise InboundWalCapacityError(
                "OneBot inbound WAL existing pending data exceeds capacity")
        self._capture_signatures()

    def _ensure_index_current(self) -> None:
        current_root = self._directory_signature(self._root)
        current_dead = self._directory_signature(self._dead)
        if current_root != self._root_signature or current_dead != self._dead_signature:
            self._rebuild_index()

    def _prune_dead(self) -> None:
        records = self._dead_records()
        remove_count = max(0, len(records) - self._max_dead_records)
        if not remove_count:
            return
        for _, _, path in sorted(records)[:remove_count]:
            path.unlink()
        self._fsync_directory(self._dead)

    def _dead_records(self) -> list[tuple[int, str, Path]]:
        records: list[tuple[int, str, Path]] = []
        for path in self._dead.iterdir():
            metadata = path.stat(follow_symlinks=False)
            if (not path.name.endswith(".dead") or not stat.S_ISREG(metadata.st_mode)
                    or stat.S_IMODE(metadata.st_mode) != 0o600):
                raise ValueError("OneBot inbound WAL dead file metadata is invalid")
            records.append((metadata.st_mtime_ns, path.name, path))
        return records

    def _discard_temporaries(self) -> None:
        changed = False
        for path in self._root.glob(".*.tmp"):
            metadata = path.stat(follow_symlinks=False)
            if not stat.S_ISREG(metadata.st_mode):
                raise ValueError("OneBot inbound WAL temporary file metadata is invalid")
            path.unlink()
            changed = True
        if changed:
            self._fsync_directory(self._root)

    def _capture_signatures(self) -> None:
        self._root_signature = self._directory_signature(self._root)
        self._dead_signature = self._directory_signature(self._dead)

    @staticmethod
    def _directory_signature(path: Path) -> tuple[int, int]:
        metadata = path.stat(follow_symlinks=False)
        return metadata.st_mtime_ns, metadata.st_ino

    @staticmethod
    def _positive_limit(value: int, name: str, maximum: int) -> int:
        if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= maximum:
            raise ValueError(f"OneBot inbound WAL {name} is invalid")
        return value

    @staticmethod
    def _safe_error_suffix(error_code: str) -> str:
        if not isinstance(error_code, str) or not 1 <= len(error_code) <= 32:
            return "UNKNOWN"
        if not error_code[0].isalpha() or not error_code[0].isupper():
            return "UNKNOWN"
        if any(not (character.isupper() or character.isdigit() or character == "_")
               for character in error_code):
            return "UNKNOWN"
        return error_code

    @staticmethod
    def _fsync_directory(path: Path) -> None:
        metadata = os.lstat(path)
        flags = os.O_RDONLY
        if hasattr(os, "O_DIRECTORY"):
            flags |= os.O_DIRECTORY
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        descriptor = os.open(path, flags)
        try:
            opened = os.fstat(descriptor)
            if (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino) \
                    or not stat.S_ISDIR(opened.st_mode):
                raise ValueError("OneBot inbound WAL directory changed before fsync")
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
