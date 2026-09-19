"""OneBot 入站本机 WAL 测试。"""

from datetime import UTC, datetime, timedelta
from hashlib import sha256
import json
import os
import stat
import threading

import pytest

from mytools_onebot_connector.inbound_wal import InboundEventWal, InboundWalCapacityError
from mytools_onebot_connector.models import InboundEvent


def event(text: str = "hello", identity: str = "default") -> InboundEvent:
    """创建一条可持久化入站事件。"""
    return InboundEvent.create(
        "qq_primary", sha256(identity.encode("utf-8")).hexdigest(), identity,
        {"ownerId": 7, "event": {"message_id": 7, "text": text}})


def test_wal_atomically_persists_and_recovers_event(tmp_path) -> None:
    """WAL 文件和目录权限应受限，重启后可恢复并确认删除。"""
    root = tmp_path / "wal"
    wal = InboundEventWal(root)
    candidate = event()

    wal.store(candidate)

    files = list(root.glob("*.json"))
    assert len(files) == 1
    assert files[0].stat().st_mode & 0o777 == 0o600
    assert root.stat().st_mode & 0o777 == 0o700
    assert list(root.glob("*.tmp")) == []
    recovered = InboundEventWal(root).load_batch(
        datetime.now(UTC) + timedelta(seconds=1), 10)
    assert len(recovered) == 1
    assert recovered[0].event.matches(candidate)

    wal.acknowledge(candidate)

    assert wal.pending_count() == 0


def test_initial_layout_fsyncs_each_new_directory_at_its_parent(
        tmp_path, monkeypatch) -> None:
    """首次布局必须按 root 父目录、dead 父目录的顺序持久化目录项。"""
    root = tmp_path / "wal"
    observations = []
    original = InboundEventWal._fsync_directory

    def observe(path):
        observations.append((path, root.exists(), (root / "dead").exists()))
        original(path)

    monkeypatch.setattr(InboundEventWal, "_fsync_directory", staticmethod(observe))
    InboundEventWal(root)

    assert observations[:2] == [
        (tmp_path, True, False),
        (root, True, True),
    ]


def test_repeated_layout_validation_replays_parent_then_root_fsyncs(
        tmp_path, monkeypatch) -> None:
    """重复启动仍补齐前进程可能中断的父目录持久化窗口。"""
    root = tmp_path / "wal"
    InboundEventWal(root)
    observations = []
    monkeypatch.setattr(
        InboundEventWal, "_fsync_directory",
        staticmethod(lambda path: observations.append(path)))

    InboundEventWal(root)

    assert observations == [tmp_path, root]


def test_layout_rejects_directory_replacement_between_lstat_and_open(
        tmp_path, monkeypatch) -> None:
    """描述符指向不同 inode 时必须拒绝继续初始化。"""
    root = tmp_path / "wal"
    original_fstat = os.fstat

    def changed_fstat(descriptor):
        metadata = original_fstat(descriptor)
        if stat.S_ISDIR(metadata.st_mode) and metadata.st_ino == tmp_path.stat().st_ino:
            values = list(metadata)
            values[1] += 1
            return os.stat_result(values)
        return metadata

    monkeypatch.setattr(os, "fstat", changed_fstat)

    with pytest.raises(ValueError, match="changed during validation"):
        InboundEventWal(root)


def test_wal_reschedule_survives_restart_and_respects_due_time(tmp_path) -> None:
    """数据库导入次数和退避时间必须随 WAL 一起持久化。"""
    wal = InboundEventWal(tmp_path / "wal")
    candidate = event()
    wal.store(candidate)
    now = datetime.now(UTC)
    entry = wal.load_batch(now + timedelta(seconds=1), 1)[0]
    retry_at = now + timedelta(seconds=60)

    wal.reschedule(entry, retry_at, "OperationalError")

    recovered = InboundEventWal(tmp_path / "wal")
    assert recovered.load_batch(now + timedelta(seconds=30), 1) == []
    due = recovered.load_batch(now + timedelta(seconds=61), 1)
    assert len(due) == 1
    assert due[0].attempt_count == 1
    assert due[0].last_error_code == "OperationalError"


def test_wal_rejects_same_key_with_changed_payload(tmp_path) -> None:
    """同一文件键不得被不同载荷覆盖。"""
    wal = InboundEventWal(tmp_path / "wal")
    wal.store(event("first"))

    with pytest.raises(ValueError, match="idempotency conflict"):
        wal.store(event("changed"))


def test_invalid_wal_is_quarantined_once(tmp_path) -> None:
    """坏文件应移入受限 dead 目录且不再自动扫描。"""
    root = tmp_path / "wal"
    wal = InboundEventWal(root)
    invalid = root / "broken.json"
    invalid.write_text("{not-json", encoding="utf-8")
    os.chmod(invalid, 0o600)

    assert wal.load_batch(datetime.now(UTC), 10) == []

    dead_files = list((root / "dead").glob("*.dead"))
    assert len(dead_files) == 1
    assert dead_files[0].stat().st_mode & 0o777 == 0o600
    assert (root / "dead").stat().st_mode & 0o777 == 0o700
    assert wal.load_batch(datetime.now(UTC), 10) == []


def test_wal_record_is_valid_bounded_json(tmp_path) -> None:
    """落盘内容应为可校验 JSON，且不产生额外临时残留。"""
    root = tmp_path / "wal"
    wal = InboundEventWal(root)
    wal.store(event())

    path = next(root.glob("*.json"))
    value = json.loads(path.read_text(encoding="utf-8"))

    assert value["version"] == 1
    assert value["importAttemptCount"] == 0
    assert value["payloadDigest"]
    assert value["payload"]["event"]["message_id"] == 7


def test_rejection_tombstone_is_durable_bounded_and_contains_no_private_data(tmp_path) -> None:
    """拒绝墓碑只能保存安全码、摘要和长度，并能在重启后计数。"""
    root = tmp_path / "wal"
    wal = InboundEventWal(root)
    private_body = b"private-message-and-user-id-123"
    digest = sha256(private_body).hexdigest()

    wal.record_rejection("MISSING_MESSAGE_ID", digest, len(private_body))

    paths = list((root / "dead").glob("*.rejected.dead"))
    assert len(paths) == 1
    assert paths[0].stat().st_mode & 0o777 == 0o600
    document = json.loads(paths[0].read_text(encoding="ascii"))
    assert document == {
        "version": 1,
        "errorCode": "MISSING_MESSAGE_ID",
        "payloadDigest": digest,
        "payloadLength": len(private_body),
    }
    assert private_body.decode() not in paths[0].read_text(encoding="ascii")
    assert "123" not in paths[0].name
    assert InboundEventWal(root).rejected_count() == 1


def test_rejection_tombstone_rejects_unsafe_metadata(tmp_path) -> None:
    """调用者不得借拒绝字段向磁盘写入私密字符串。"""
    wal = InboundEventWal(tmp_path / "wal")
    with pytest.raises(ValueError, match="metadata"):
        wal.record_rejection("private-error", "0" * 64, 10)
    with pytest.raises(ValueError, match="metadata"):
        wal.record_rejection("INVALID_JSON", "not-a-digest", 10)


def test_pending_count_limit_rejects_atomically_and_keeps_existing_data(tmp_path) -> None:
    """数量满时应允许幂等重放，但原子拒绝新的帧且不改已有文件。"""
    root = tmp_path / "wal"
    wal = InboundEventWal(root, max_pending_records=1)
    first = event("first-private-body", "first")
    wal.store(first)
    original = next(root.glob("*.json")).read_bytes()

    assert wal.store(first).matches(first)
    with pytest.raises(InboundWalCapacityError, match="record limit"):
        wal.store(event("second-private-body", "second"))

    assert wal.pending_count() == 1
    assert len(list(root.glob("*.json"))) == 1
    assert next(root.glob("*.json")).read_bytes() == original


def test_total_payload_limit_rejects_new_frame_without_partial_file(tmp_path) -> None:
    """总载荷满时不得留下新 JSON 或临时文件。"""
    root = tmp_path / "wal"
    first = event("first", "first")
    payload_bytes = len(first.payload_json.encode("utf-8"))
    wal = InboundEventWal(root, max_pending_records=10,
                          max_total_payload_bytes=payload_bytes)
    wal.store(first)

    with pytest.raises(InboundWalCapacityError, match="payload capacity"):
        wal.store(event("second", "second"))

    assert wal.pending_payload_bytes() == payload_bytes
    assert len(list(root.glob("*.json"))) == 1
    assert list(root.glob(".*.tmp")) == []


def test_concurrent_store_cannot_oversubscribe_pending_capacity(tmp_path) -> None:
    """并发 store 必须在同一容量锁内只接纳一条记录。"""
    wal = InboundEventWal(tmp_path / "wal", max_pending_records=1)
    barrier = threading.Barrier(3)
    stored: list[str] = []
    rejected: list[str] = []

    def write(identity: str) -> None:
        barrier.wait()
        try:
            wal.store(event(identity, identity))
            stored.append(identity)
        except InboundWalCapacityError:
            rejected.append(identity)

    threads = [threading.Thread(target=write, args=(identity,))
               for identity in ("first", "second")]
    for thread in threads:
        thread.start()
    barrier.wait()
    for thread in threads:
        thread.join()

    assert len(stored) == 1
    assert len(rejected) == 1
    assert wal.pending_count() == 1


def test_restart_rejects_over_capacity_without_deleting_pending_data(tmp_path) -> None:
    """降低上限后的重启应失败关闭，而不是删除已落盘 pending。"""
    root = tmp_path / "wal"
    wal = InboundEventWal(root, max_pending_records=2)
    wal.store(event("first", "first"))
    wal.store(event("second", "second"))

    with pytest.raises(InboundWalCapacityError, match="existing pending"):
        InboundEventWal(root, max_pending_records=1)

    assert len(list(root.glob("*.json"))) == 2


def test_dead_retention_keeps_latest_records_and_never_requeues(tmp_path) -> None:
    """dead 只保留最近 N 条，重启和扫描均不得自动重投。"""
    root = tmp_path / "wal"
    wal = InboundEventWal(root, max_dead_records=2)
    dead_sets: list[set[str]] = []
    for position, identity in enumerate(("first", "second", "third"), start=1):
        candidate = event(f"private-{identity}", identity)
        wal.store(candidate)
        wal.mark_dead(candidate, "DB_IMPORT_EXHAUSTED")
        current = set(path.name for path in (root / "dead").glob("*.dead"))
        newest = current - (dead_sets[-1] if dead_sets else set())
        for name in newest:
            os.utime(root / "dead" / name, ns=(position, position))
        dead_sets.append(current)

    retained = set(path.name for path in (root / "dead").glob("*.dead"))
    assert len(retained) == 2
    assert dead_sets[0].isdisjoint(retained)
    assert dead_sets[1] & retained
    assert InboundEventWal(root, max_dead_records=2).load_batch(
        datetime.now(UTC) + timedelta(days=1), 10) == []
    assert wal.pending_count() == 0


def test_quarantine_hides_source_name_and_payload_from_logs_and_filename(
        tmp_path, caplog) -> None:
    """隔离日志和 dead 文件名不得包含来源名称或正文。"""
    root = tmp_path / "wal"
    wal = InboundEventWal(root, max_dead_records=2)
    private_name = "private-user-identity"
    private_body = "private-message-body"
    invalid = root / f"{private_name}.json"
    invalid.write_text(private_body, encoding="utf-8")
    os.chmod(invalid, 0o600)

    assert wal.load_batch(datetime.now(UTC), 10) == []

    assert private_name not in caplog.text
    assert private_body not in caplog.text
    assert all(private_name not in path.name for path in (root / "dead").iterdir())

    candidate = event("another-private-body", "safe-error")
    wal.store(candidate)
    wal.mark_dead(candidate, "private_secret_code")
    assert all("private_secret_code" not in path.name
               for path in (root / "dead").iterdir())


@pytest.mark.parametrize("name,value", [
    ("max_pending_records", 0),
    ("max_total_payload_bytes", 0),
    ("max_dead_records", 0),
])
def test_capacity_configuration_must_be_positive(tmp_path, name, value) -> None:
    """非法容量配置必须在连接渠道前失败。"""
    with pytest.raises(ValueError, match=name):
        InboundEventWal(tmp_path / name, **{name: value})
