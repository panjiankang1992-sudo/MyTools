"""QQ 出站文本的本机持久幂等日志。"""
from __future__ import annotations

from dataclasses import dataclass
import fcntl
import heapq
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import stat
import tempfile
import time


EVENT_KEY_PATTERN = re.compile(r"^[0-9a-f]{64}$")
RECORD_STATES = frozenset({"PENDING", "CLAIMED", "DONE", "DEAD"})
GENERATION_BYTES = 16
RECORD_OVERHEAD_BYTES = 64 * 1024


class OutboundWalError(RuntimeError):
    """出站日志基础异常。"""


class OutboundWalConflictError(OutboundWalError):
    """同一幂等键对应不同载荷。"""


class OutboundWalCapacityError(OutboundWalError):
    """出站日志容量已满。"""


class OutboundWalDeadError(OutboundWalError):
    """出站记录已进入死亡状态。"""


class OutboundWalDeferredError(OutboundWalError):
    """出站记录仍在租约或退避窗口内。"""

    def __init__(self, retry_after_seconds: int) -> None:
        super().__init__("outbound delivery is deferred")
        self.retry_after_seconds = max(1, min(60, retry_after_seconds))


class OutboundWalStaleClaimError(OutboundWalError):
    """出站租约已被其他请求接管。"""


@dataclass(frozen=True, slots=True)
class OutboundClaim:
    """一次获得互斥租约的出站投递。"""

    event_key: str
    payload: dict
    attempt: int
    claim_token: str


@dataclass(frozen=True, slots=True)
class _RecordSummary:
    """内存索引中的最小非敏感记录状态。"""

    state: str
    modified_ns: int
    inode: int


class OutboundTextWal:
    """以鉴权幂等键和稳定载荷摘要维护有界出站状态。"""

    def __init__(self, root: str, max_records: int = 10_000,
                 max_payload_bytes: int = 16 * 1024,
                 max_attempts: int = 9, lease_seconds: float = 60.0) -> None:
        """初始化日志并校验安全边界。"""
        if isinstance(max_records, bool) or not isinstance(max_records, int) \
                or max_records < 1 \
                or isinstance(max_payload_bytes, bool) \
                or not isinstance(max_payload_bytes, int) \
                or max_payload_bytes < 128 \
                or isinstance(max_attempts, bool) \
                or not isinstance(max_attempts, int) or max_attempts < 1 \
                or isinstance(lease_seconds, bool) \
                or not isinstance(lease_seconds, (int, float)) \
                or not math.isfinite(lease_seconds) or lease_seconds <= 0:
            raise ValueError("outbound WAL settings are invalid")
        self.root = Path(root)
        self.max_records = max_records
        self.max_payload_bytes = max_payload_bytes
        self._record_read_limit = max_payload_bytes + RECORD_OVERHEAD_BYTES
        self.max_attempts = max_attempts
        self.lease_seconds = lease_seconds
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self.root.is_symlink():
            raise OutboundWalError("outbound WAL root must not be a symlink")
        os.chmod(self.root, 0o700)
        self.lock_path = self.root / ".lock"
        descriptor = os.open(self.lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        os.close(descriptor)
        # 首次创建目录和锁文件后同步父目录及 WAL 目录，保证断电恢复可见。
        self._fsync_path(self.root.parent)
        self._fsync_path(self.root)
        self._records: dict[str, _RecordSummary] = {}
        self._state_counts = {state: 0 for state in RECORD_STATES}
        self._done_heap: list[tuple[int, int, str]] = []
        self._generation: bytes | None = None
        self._root_signature: tuple[int, int, int, int, int] | None = None
        with self._lock():
            self._initialize_generation()
            self._rebuild_index()

    @staticmethod
    def event_key(idempotency_key: str) -> str:
        """返回不暴露原始幂等键的稳定事件键。"""
        if not isinstance(idempotency_key, str) or not idempotency_key \
                or len(idempotency_key) > 255:
            raise ValueError("idempotencyKey is required and bounded")
        return hashlib.sha256(idempotency_key.encode()).hexdigest()

    @staticmethod
    def digest(payload: dict) -> str:
        """返回规范 JSON 载荷摘要。"""
        raw = json.dumps(payload, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode()
        return hashlib.sha256(raw).hexdigest()

    def claim(self, idempotency_key: str, payload: dict,
              now: float | None = None) -> OutboundClaim | None:
        """原子创建或租用记录；DONE 返回空，冲突和 DEAD 显式报错。"""
        current_time = time.time() if now is None else now
        self._validate_time(current_time, "now")
        if not isinstance(payload, dict):
            raise TypeError("outbound payload must be an object")
        raw = json.dumps(payload, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode()
        if len(raw) > self.max_payload_bytes:
            raise OutboundWalCapacityError("outbound payload is too large")
        key, digest = self.event_key(idempotency_key), hashlib.sha256(raw).hexdigest()
        with self._lock():
            self._ensure_index_current()
            path = self.root / f"{key}.json"
            # 即使目录签名在极端文件系统上发生碰撞，也直接探测目标，
            # 防止覆盖其他进程已落盘的同键结果。
            record = self._read(path) if os.path.lexists(path) else None
            if record is None:
                while len(self._records) >= self.max_records:
                    # DONE 仅是有界墓碑；优先淘汰最旧墓碑，不挤掉待发送或 DEAD。
                    oldest_done = self._oldest_done()
                    if oldest_done is None:
                        raise OutboundWalCapacityError("outbound WAL is full")
                    done_path = self.root / f"{oldest_done}.json"
                    summary = self._records[oldest_done]
                    try:
                        persisted = self._read(done_path)
                        metadata = os.stat(done_path, follow_symlinks=False)
                    except (OutboundWalError, FileNotFoundError):
                        if not os.path.lexists(done_path):
                            self._rebuild_index()
                            continue
                        raise
                    if persisted["state"] != "DONE" \
                            or metadata.st_mtime_ns != summary.modified_ns \
                            or metadata.st_ino != summary.inode:
                        # 极端目录签名碰撞也不能删除已被重建为活跃状态的同名记录。
                        self._rebuild_index()
                        continue
                    generation = self._rotate_generation()
                    try:
                        os.unlink(done_path)
                    except FileNotFoundError:
                        # 非协作运维若恰好删除目标，重建后重新选择，不能丢失堆候选。
                        self._rebuild_index()
                        continue
                    self._fsync_directory()
                    self._remove_index(oldest_done)
                    self._generation = generation
                    self._root_signature = self._directory_signature()
                record = {"version": 1, "key": key, "digest": digest,
                          "payload": payload, "state": "PENDING", "attempt": 0,
                          "claimToken": None, "leaseUntil": 0.0,
                          "nextAt": 0.0, "permanent": False, "error": None}
            elif record["digest"] != digest:
                raise OutboundWalConflictError("idempotency key has conflicting payload")
            if record["state"] == "DONE":
                return None
            if record["state"] == "DEAD":
                raise OutboundWalDeadError("outbound delivery exhausted retries")
            if record["nextAt"] > current_time or record["leaseUntil"] > current_time:
                retry_at = max(record["nextAt"], record["leaseUntil"])
                raise OutboundWalDeferredError(math.ceil(retry_at - current_time))
            if record["attempt"] >= self.max_attempts:
                record.update({"state": "DEAD", "claimToken": None,
                               "leaseUntil": 0.0, "nextAt": 0.0})
                self._write(path, record)
                raise OutboundWalDeadError("outbound delivery exhausted retries")
            record["attempt"] += 1
            record["state"] = "CLAIMED"
            record["claimToken"] = secrets.token_hex(16)
            record["leaseUntil"] = current_time + self.lease_seconds
            self._write(path, record)
            return OutboundClaim(
                key, dict(payload), record["attempt"], record["claimToken"])

    def complete(self, claim: OutboundClaim) -> None:
        """原子标记发送成功并移除原始载荷。"""
        self._validate_claim(claim)
        with self._lock():
            self._ensure_index_current()
            path = self.root / f"{claim.event_key}.json"
            record = self._read(path)
            self._require_owned_claim(record, claim)
            record.update({"state": "DONE", "payload": None,
                           "claimToken": None, "leaseUntil": 0.0,
                           "nextAt": 0.0, "error": None})
            self._write(path, record)

    def fail(self, claim: OutboundClaim, error: str, permanent: bool = False,
             now: float | None = None) -> bool:
        """记录失败；返回是否已经进入 DEAD。"""
        self._validate_claim(claim)
        if not isinstance(permanent, bool):
            raise TypeError("permanent must be a boolean")
        failure_time = time.time() if now is None else now
        self._validate_time(failure_time, "now")
        with self._lock():
            self._ensure_index_current()
            path = self.root / f"{claim.event_key}.json"
            record = self._read(path)
            self._require_owned_claim(record, claim)
            dead = permanent or record["attempt"] >= self.max_attempts
            record.update({"state": "DEAD" if dead else "PENDING",
                           "permanent": permanent,
                           "claimToken": None,
                           "leaseUntil": 0.0,
                           "nextAt": 0.0 if dead else failure_time
                           + min(60.0, 2 ** max(0, record["attempt"] - 1)),
                           "error": str(error)[:512]})
            self._write(path, record)
            return dead

    def redrive(self, event_key: str) -> dict:
        """恢复 DEAD，或继续一次已开始但中断的人工重投。"""
        self._validate_event_key(event_key)
        with self._lock():
            self._ensure_index_current()
            path = self.root / f"{event_key}.json"
            record = self._read(path)
            if record["state"] == "DONE" or record.get("permanent", False):
                raise OutboundWalError("outbound record is not retryable")
            if record["state"] == "DEAD":
                record.update({"state": "PENDING", "attempt": 0,
                               "leaseUntil": 0.0, "claimToken": None,
                               "nextAt": 0.0, "error": None})
                self._write(path, record)
            # PENDING/CLAIMED 表示之前的人工重投可能在发送前崩溃或发送后暂时失败；
            # 返回同一已校验载荷，使相同端点可安全续跑，实际 claim 仍受退避和租约保护。
            return dict(record["payload"])

    def stats(self) -> dict:
        """返回不包含幂等键和载荷的状态计数。"""
        with self._lock():
            self._ensure_index_current()
            return {state.lower(): self._state_counts[state]
                    for state in ("PENDING", "CLAIMED", "DONE", "DEAD")}

    def _lock(self):
        descriptor = os.open(self.lock_path, os.O_RDWR | os.O_NOFOLLOW)
        class Lock:
            def __enter__(inner):
                fcntl.flock(descriptor, fcntl.LOCK_EX)
            def __exit__(inner, *_args):
                fcntl.flock(descriptor, fcntl.LOCK_UN)
                os.close(descriptor)
        return Lock()

    @staticmethod
    def _validate_event_key(event_key: str) -> None:
        if not isinstance(event_key, str) or EVENT_KEY_PATTERN.fullmatch(event_key) is None:
            raise ValueError("event key must be 64 lowercase hexadecimal characters")

    @classmethod
    def _validate_claim(cls, claim: OutboundClaim) -> None:
        if not isinstance(claim, OutboundClaim):
            raise TypeError("claim must be OutboundClaim")
        cls._validate_event_key(claim.event_key)
        if isinstance(claim.attempt, bool) \
                or not isinstance(claim.attempt, int) or claim.attempt < 1 \
                or not isinstance(claim.claim_token, str) \
                or re.fullmatch(r"[0-9a-f]{32}", claim.claim_token) is None:
            raise ValueError("outbound claim is invalid")

    @staticmethod
    def _validate_time(value: float, name: str) -> None:
        """校验外部传入的时间边界。"""
        if isinstance(value, bool) or not isinstance(value, (int, float)) \
                or not math.isfinite(value) or value < 0:
            raise ValueError(f"{name} must be a finite non-negative number")

    @staticmethod
    def _require_owned_claim(record: dict, claim: OutboundClaim) -> None:
        if record["state"] != "CLAIMED" \
                or record.get("claimToken") != claim.claim_token \
                or record["attempt"] != claim.attempt:
            raise OutboundWalStaleClaimError("outbound claim is stale")

    def _read(self, path: Path) -> dict:
        descriptor = -1
        try:
            flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) \
                | getattr(os, "O_NONBLOCK", 0)
            descriptor = os.open(path, flags)
            metadata = os.fstat(descriptor)
            if not stat.S_ISREG(metadata.st_mode):
                raise OutboundWalError(
                    "outbound WAL record must be a regular file")
            if metadata.st_size > self._record_read_limit:
                raise OutboundWalError("outbound WAL record is too large")
            with os.fdopen(descriptor, "r", encoding="utf-8") as stream:
                descriptor = -1
                raw = stream.read(self._record_read_limit + 1)
            if len(raw.encode()) > self._record_read_limit:
                raise OutboundWalError("outbound WAL record is too large")
            record = json.loads(raw)
        except OutboundWalError:
            raise
        except (OSError, UnicodeError, json.JSONDecodeError) as exception:
            raise OutboundWalError("outbound WAL record is unreadable") from exception
        finally:
            if descriptor >= 0:
                os.close(descriptor)
        expected_key = path.stem
        lease_until = record.get("leaseUntil") if isinstance(record, dict) else None
        next_at = record.get("nextAt") if isinstance(record, dict) else None
        permanent = record.get("permanent", False) if isinstance(record, dict) else None
        error = record.get("error") if isinstance(record, dict) else None
        if not isinstance(record, dict) or record.get("version") != 1 \
                or record.get("key") != expected_key \
                or EVENT_KEY_PATTERN.fullmatch(expected_key) is None \
                or not isinstance(record.get("digest"), str) \
                or EVENT_KEY_PATTERN.fullmatch(record["digest"]) is None \
                or record.get("state") not in RECORD_STATES \
                or (record.get("state") == "CLAIMED" and (
                    not isinstance(record.get("claimToken"), str)
                    or re.fullmatch(r"[0-9a-f]{32}", record["claimToken"]) is None)) \
                or (record.get("state") != "CLAIMED"
                    and record.get("claimToken") is not None) \
                or isinstance(record.get("attempt"), bool) \
                or not isinstance(record.get("attempt"), int) \
                or record["attempt"] < 0 \
                or isinstance(lease_until, bool) \
                or not isinstance(lease_until, (int, float)) \
                or not math.isfinite(lease_until) or lease_until < 0 \
                or isinstance(next_at, bool) \
                or not isinstance(next_at, (int, float)) \
                or not math.isfinite(next_at) or next_at < 0 \
                or not isinstance(permanent, bool) \
                or (permanent and record.get("state") != "DEAD") \
                or (error is not None and (
                    not isinstance(error, str) or len(error) > 512)):
            raise OutboundWalError("outbound WAL record schema is invalid")
        payload = record.get("payload")
        payload_bytes = None if not isinstance(payload, dict) else json.dumps(
            payload, ensure_ascii=False, sort_keys=True,
            separators=(",", ":")).encode()
        if (record["state"] == "DONE" and payload is not None) \
                or (record["state"] != "DONE" and not isinstance(payload, dict)) \
                or (payload_bytes is not None and (
                    len(payload_bytes) > self.max_payload_bytes
                    or hashlib.sha256(payload_bytes).hexdigest() != record["digest"])):
            raise OutboundWalError("outbound WAL payload integrity is invalid")
        return record

    def _write(self, path: Path, record: dict) -> None:
        # 先持久化共享代际；之后任一位置崩溃，其他进程都会重建索引。
        generation = self._rotate_generation()
        descriptor, temporary = tempfile.mkstemp(prefix=".tmp-", dir=self.root)
        try:
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                json.dump(record, stream, ensure_ascii=False, sort_keys=True,
                          separators=(",", ":"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
            self._fsync_directory()
            self._index_record(path, record)
            self._generation = generation
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def _ensure_index_current(self) -> None:
        """检测其他进程的原子变更，并在需要时重建内存索引。"""
        if self._generation != self._read_generation() \
                or self._root_signature != self._directory_signature():
            self._rebuild_index()

    def _rebuild_index(self) -> None:
        """从已校验的持久记录重建不含业务载荷的内存索引。"""
        records: dict[str, _RecordSummary] = {}
        state_counts = {state: 0 for state in RECORD_STATES}
        done_heap: list[tuple[int, int, str]] = []
        for path in self._record_paths():
            record = self._read(path)
            metadata = os.stat(path, follow_symlinks=False)
            if not stat.S_ISREG(metadata.st_mode):
                raise OutboundWalError("outbound WAL record must be a regular file")
            summary = _RecordSummary(
                record["state"], metadata.st_mtime_ns, metadata.st_ino)
            records[path.stem] = summary
            state_counts[summary.state] += 1
            if summary.state == "DONE":
                heapq.heappush(done_heap, (
                    summary.modified_ns, summary.inode, path.stem))
        if len(records) > self.max_records:
            raise OutboundWalCapacityError(
                "outbound WAL exceeds configured capacity")
        self._records = records
        self._state_counts = state_counts
        self._done_heap = done_heap
        self._generation = self._read_generation()
        self._root_signature = self._directory_signature()

    def _index_record(self, path: Path, record: dict) -> None:
        """在原子写成功后更新单条索引。"""
        previous = self._records.get(path.stem)
        if previous is not None:
            self._state_counts[previous.state] -= 1
        metadata = os.stat(path, follow_symlinks=False)
        if not stat.S_ISREG(metadata.st_mode):
            raise OutboundWalError("outbound WAL record must be a regular file")
        summary = _RecordSummary(
            record["state"], metadata.st_mtime_ns, metadata.st_ino)
        self._records[path.stem] = summary
        self._state_counts[summary.state] += 1
        if summary.state == "DONE":
            heapq.heappush(self._done_heap, (
                summary.modified_ns, summary.inode, path.stem))
        self._root_signature = self._directory_signature()

    def _remove_index(self, event_key: str) -> None:
        """从索引移除已经持久删除的记录。"""
        previous = self._records.pop(event_key, None)
        if previous is not None:
            self._state_counts[previous.state] -= 1

    def _oldest_done(self) -> str | None:
        """以惰性清理方式返回最旧的有效 DONE 墓碑。"""
        while self._done_heap:
            modified_ns, inode, event_key = self._done_heap[0]
            summary = self._records.get(event_key)
            if summary is not None and summary.state == "DONE" \
                    and summary.modified_ns == modified_ns \
                    and summary.inode == inode:
                return event_key
            heapq.heappop(self._done_heap)
        return None

    def _initialize_generation(self) -> None:
        """初始化或读取与锁文件共存的固定长度共享代际。"""
        descriptor = -1
        try:
            descriptor = os.open(
                self.lock_path, os.O_RDWR | getattr(os, "O_NOFOLLOW", 0))
            metadata = os.fstat(descriptor)
            if not stat.S_ISREG(metadata.st_mode):
                raise OutboundWalError("outbound WAL lock must be a regular file")
            if metadata.st_size == 0:
                token = secrets.token_bytes(GENERATION_BYTES)
                self._write_all(descriptor, token)
                os.ftruncate(descriptor, GENERATION_BYTES)
                os.fsync(descriptor)
                self._generation = token
            elif metadata.st_size == GENERATION_BYTES:
                self._generation = self._read_exact(descriptor, GENERATION_BYTES)
            else:
                raise OutboundWalError("outbound WAL generation is invalid")
        except OutboundWalError:
            raise
        except OSError as exception:
            raise OutboundWalError("unable to initialize outbound WAL generation") \
                from exception
        finally:
            if descriptor >= 0:
                os.close(descriptor)

    def _read_generation(self) -> bytes:
        """读取固定长度共享代际，发现损坏时失败关闭。"""
        descriptor = -1
        try:
            descriptor = os.open(
                self.lock_path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
            metadata = os.fstat(descriptor)
            if not stat.S_ISREG(metadata.st_mode) \
                    or metadata.st_size != GENERATION_BYTES:
                raise OutboundWalError("outbound WAL generation is invalid")
            return self._read_exact(descriptor, GENERATION_BYTES)
        except OutboundWalError:
            raise
        except OSError as exception:
            raise OutboundWalError("unable to read outbound WAL generation") \
                from exception
        finally:
            if descriptor >= 0:
                os.close(descriptor)

    def _rotate_generation(self) -> bytes:
        """在持久状态变更前推进共享代际。"""
        descriptor = -1
        token = secrets.token_bytes(GENERATION_BYTES)
        try:
            descriptor = os.open(
                self.lock_path, os.O_RDWR | getattr(os, "O_NOFOLLOW", 0))
            metadata = os.fstat(descriptor)
            if not stat.S_ISREG(metadata.st_mode) \
                    or metadata.st_size != GENERATION_BYTES:
                raise OutboundWalError("outbound WAL generation is invalid")
            self._write_all(descriptor, token)
            os.ftruncate(descriptor, GENERATION_BYTES)
            os.fsync(descriptor)
            return token
        except OutboundWalError:
            raise
        except OSError as exception:
            raise OutboundWalError("unable to rotate outbound WAL generation") \
                from exception
        finally:
            if descriptor >= 0:
                os.close(descriptor)

    def _record_paths(self) -> tuple[Path, ...]:
        """枚举记录并安全清理崩溃遗留的临时文件。"""
        records: list[Path] = []
        removed_temporary = False
        try:
            entries = tuple(self.root.iterdir())
        except OSError as exception:
            raise OutboundWalError("unable to enumerate outbound WAL") from exception
        for path in entries:
            if path.name == ".lock":
                continue
            metadata = os.lstat(path)
            if path.name.startswith(".tmp-"):
                if not (stat.S_ISREG(metadata.st_mode)
                        or stat.S_ISLNK(metadata.st_mode)):
                    raise OutboundWalError(
                        "outbound WAL temporary entry has unsafe type")
                if not removed_temporary:
                    self._rotate_generation()
                os.unlink(path)
                removed_temporary = True
                continue
            if re.fullmatch(r"[0-9a-f]{64}\.json", path.name) is None \
                    or not stat.S_ISREG(metadata.st_mode):
                raise OutboundWalError("unexpected outbound WAL entry")
            records.append(path)
        if removed_temporary:
            self._fsync_directory()
        return tuple(records)

    @staticmethod
    def _read_exact(descriptor: int, size: int) -> bytes:
        """从文件起始位置读取固定长度数据。"""
        os.lseek(descriptor, 0, os.SEEK_SET)
        chunks: list[bytes] = []
        remaining = size
        while remaining:
            chunk = os.read(descriptor, remaining)
            if not chunk:
                raise OutboundWalError("outbound WAL generation is truncated")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)

    @staticmethod
    def _write_all(descriptor: int, data: bytes) -> None:
        """从文件起始位置完整写入代际数据。"""
        os.lseek(descriptor, 0, os.SEEK_SET)
        offset = 0
        while offset < len(data):
            written = os.write(descriptor, data[offset:])
            if written <= 0:
                raise OutboundWalError("unable to persist outbound WAL generation")
            offset += written

    def _directory_signature(self) -> tuple[int, int, int, int, int]:
        """返回可发现跨进程原子增删改的目录签名。"""
        metadata = os.stat(self.root, follow_symlinks=False)
        if not stat.S_ISDIR(metadata.st_mode):
            raise OutboundWalError("outbound WAL root must be a directory")
        return (metadata.st_dev, metadata.st_ino, metadata.st_mtime_ns,
                metadata.st_ctime_ns, metadata.st_size)

    def _fsync_directory(self) -> None:
        self._fsync_path(self.root)

    @staticmethod
    def _fsync_path(path: Path) -> None:
        directory = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
