import json
import os

import pytest

from mytools_qq_connector.outbound_wal import (
    OutboundClaim, OutboundTextWal, OutboundWalCapacityError,
    OutboundWalConflictError, OutboundWalDeadError, OutboundWalDeferredError,
    OutboundWalError, OutboundWalStaleClaimError)


PAYLOAD = {"sender": "u", "messageId": "m", "text": "done",
           "idempotencyKey": "completion-1"}


def test_success_repeat_and_restart(tmp_path):
    wal = OutboundTextWal(str(tmp_path))
    claim = wal.claim("completion-1", PAYLOAD, now=10)
    wal.complete(claim)
    assert wal.claim("completion-1", PAYLOAD, now=20) is None
    assert OutboundTextWal(str(tmp_path)).claim("completion-1", PAYLOAD, now=30) is None
    assert wal.stats() == {"pending": 0, "claimed": 0, "done": 1, "dead": 0}


def test_conflicting_payload_is_rejected(tmp_path):
    wal = OutboundTextWal(str(tmp_path))
    wal.claim("completion-1", PAYLOAD, now=10)
    with pytest.raises(OutboundWalConflictError):
        wal.claim("completion-1", {**PAYLOAD, "text": "other"}, now=100)


def test_concurrent_lease_and_crash_recovery(tmp_path):
    wal = OutboundTextWal(str(tmp_path), lease_seconds=5)
    first = wal.claim("completion-1", PAYLOAD, now=10)
    with pytest.raises(OutboundWalDeferredError) as deferred:
        wal.claim("completion-1", PAYLOAD, now=11)
    assert deferred.value.retry_after_seconds == 4
    recovered = OutboundTextWal(str(tmp_path), lease_seconds=5).claim(
        "completion-1", PAYLOAD, now=16)
    assert recovered.event_key == first.event_key
    assert recovered.attempt == 2


def test_retry_budget_dead_and_manual_redrive(tmp_path):
    wal = OutboundTextWal(str(tmp_path), max_attempts=2, lease_seconds=1)
    first = wal.claim("completion-1", PAYLOAD, now=1)
    assert wal.fail(first, "timeout", now=1) is False
    second = wal.claim("completion-1", PAYLOAD, now=2)
    assert wal.fail(second, "timeout", now=2) is True
    with pytest.raises(OutboundWalDeadError):
        wal.claim("completion-1", PAYLOAD, now=100)
    assert wal.redrive(first.event_key) == PAYLOAD
    assert wal.claim("completion-1", PAYLOAD, now=101).attempt == 1


def test_permanent_dead_cannot_redrive(tmp_path):
    wal = OutboundTextWal(str(tmp_path))
    claim = wal.claim("completion-1", PAYLOAD, now=1)
    assert wal.fail(claim, "bad request", permanent=True) is True
    with pytest.raises(OutboundWalError):
        wal.redrive(claim.event_key)


def test_record_and_payload_caps_and_permissions(tmp_path):
    wal = OutboundTextWal(str(tmp_path), max_records=1, max_payload_bytes=128)
    wal.claim("completion-1", PAYLOAD, now=1)
    with pytest.raises(OutboundWalCapacityError):
        wal.claim("completion-2", {**PAYLOAD, "idempotencyKey": "completion-2"}, now=1)
    with pytest.raises(OutboundWalCapacityError):
        OutboundTextWal(str(tmp_path / "other"), max_payload_bytes=128).claim(
            "large", {"text": "x" * 200}, now=1)
    assert os.stat(tmp_path).st_mode & 0o777 == 0o700
    record = next(tmp_path.glob("*.json"))
    assert os.stat(record).st_mode & 0o777 == 0o600
    assert json.loads(record.read_text())["payload"] == PAYLOAD


def test_old_done_tombstone_is_pruned_before_rejecting_new_work(tmp_path):
    wal = OutboundTextWal(str(tmp_path), max_records=1)
    first = wal.claim("completion-1", PAYLOAD, now=1)
    wal.complete(first)
    second_payload = {**PAYLOAD, "idempotencyKey": "completion-2"}
    assert wal.claim("completion-2", second_payload, now=2).attempt == 1
    assert wal.stats() == {"pending": 0, "claimed": 1, "done": 0, "dead": 0}


@pytest.mark.parametrize("event_key", ["../outside", "A" * 64, "a" * 63, "a" * 65])
def test_mutations_reject_noncanonical_event_keys_without_touching_outside(
        tmp_path, event_key):
    outside = tmp_path.parent / "outside.json"
    outside.write_text("sentinel", encoding="utf-8")
    wal = OutboundTextWal(str(tmp_path))
    invalid_claim = OutboundClaim(event_key, PAYLOAD, 1, "0" * 32)
    for operation in (lambda key: wal.complete(invalid_claim), wal.redrive,
                      lambda key: wal.fail(invalid_claim, "failure")):
        with pytest.raises(ValueError):
            operation(event_key)
    assert outside.read_text(encoding="utf-8") == "sentinel"
    assert list(tmp_path.glob("*.json")) == []


@pytest.mark.parametrize("mutation", [
    lambda record: [],
    lambda record: {**record, "state": "UNKNOWN"},
    lambda record: {**record, "key": "b" * 64},
    lambda record: {**record, "digest": "invalid"},
    lambda record: {**record, "payload": {"text": "tampered"}},
    lambda record: {**record, "attempt": True},
    lambda record: {**record, "leaseUntil": float("nan")},
    lambda record: {**record, "nextAt": float("inf")},
    lambda record: {**record, "permanent": "yes"},
])
def test_read_rejects_malformed_or_mismatched_records(tmp_path, mutation):
    wal = OutboundTextWal(str(tmp_path))
    claim = wal.claim("completion-1", PAYLOAD, now=1)
    path = tmp_path / f"{claim.event_key}.json"
    record = json.loads(path.read_text(encoding="utf-8"))
    path.write_text(json.dumps(mutation(record)), encoding="utf-8")
    with pytest.raises(OutboundWalError):
        # 原地篡改不会改变父目录版本，但重启恢复必须完整校验全部记录。
        OutboundTextWal(str(tmp_path))


def test_cross_process_atomic_change_refreshes_cached_index(tmp_path):
    first = OutboundTextWal(str(tmp_path))
    second = OutboundTextWal(str(tmp_path))
    claim = first.claim("completion-1", PAYLOAD, now=1)
    first.complete(claim)

    assert second.stats() == {
        "pending": 0, "claimed": 0, "done": 1, "dead": 0}
    assert second.claim("completion-1", PAYLOAD, now=2) is None


def test_hot_stats_and_capacity_prune_do_not_rescan_payloads(tmp_path, monkeypatch):
    wal = OutboundTextWal(str(tmp_path), max_records=64)
    for index in range(64):
        payload = {**PAYLOAD, "idempotencyKey": f"completion-{index}"}
        claim = wal.claim(f"completion-{index}", payload, now=index + 1)
        wal.complete(claim)

    reads = 0
    original_read = OutboundTextWal._read

    def count_read(instance, path):
        nonlocal reads
        reads += 1
        return original_read(instance, path)

    monkeypatch.setattr(OutboundTextWal, "_read", count_read)
    assert wal.stats()["done"] == 64
    assert reads == 0

    payload = {**PAYLOAD, "idempotencyKey": "completion-new"}
    claim = wal.claim("completion-new", payload, now=100)
    # 满容量时只复核一个待淘汰墓碑，不随目录记录数线性扫描。
    assert reads == 1
    wal.complete(claim)
    assert reads == 2


def test_shared_generation_detects_change_when_directory_signature_collides(
        tmp_path, monkeypatch):
    signature = (1, 2, 3, 4, 5)
    monkeypatch.setattr(
        OutboundTextWal, "_directory_signature", lambda _self: signature)
    first = OutboundTextWal(str(tmp_path), max_records=1)
    second = OutboundTextWal(str(tmp_path), max_records=1)

    first.claim("completion-1", PAYLOAD, now=1)
    with pytest.raises(OutboundWalCapacityError):
        second.claim(
            "completion-2", {**PAYLOAD, "idempotencyKey": "completion-2"}, now=2)


def test_unconfirmed_generation_rebuilds_after_post_replace_failure(
        tmp_path, monkeypatch):
    wal = OutboundTextWal(str(tmp_path))
    original_index_record = wal._index_record

    def fail_after_replace(_path, _record):
        raise OSError("injected index failure")

    monkeypatch.setattr(wal, "_index_record", fail_after_replace)
    with pytest.raises(OSError):
        wal.claim("completion-1", PAYLOAD, now=1)
    monkeypatch.setattr(wal, "_index_record", original_index_record)

    assert wal.stats() == {
        "pending": 0, "claimed": 1, "done": 0, "dead": 0}


def test_stale_done_candidate_never_deletes_recreated_active_record(
        tmp_path, monkeypatch):
    wal = OutboundTextWal(str(tmp_path), max_records=1)
    finished = wal.claim("completion-1", PAYLOAD, now=1)
    wal.complete(finished)
    path = tmp_path / f"{finished.event_key}.json"
    record = json.loads(path.read_text(encoding="utf-8"))
    record.update({"state": "CLAIMED", "payload": PAYLOAD, "attempt": 2,
                   "claimToken": "f" * 32, "leaseUntil": 100.0})
    replacement = tmp_path / ".replacement"
    replacement.write_text(json.dumps(record), encoding="utf-8")
    os.replace(replacement, path)

    # 模拟目录签名碰撞；共享代际未变化，淘汰前的直接复核仍必须保护活记录。
    signature = wal._root_signature
    monkeypatch.setattr(wal, "_directory_signature", lambda: signature)
    with pytest.raises(OutboundWalCapacityError):
        wal.claim(
            "completion-2", {**PAYLOAD, "idempotencyKey": "completion-2"}, now=2)
    assert json.loads(path.read_text(encoding="utf-8"))["state"] == "CLAIMED"


def test_recovery_removes_crash_temporary_and_rejects_unsafe_entries(tmp_path):
    OutboundTextWal(str(tmp_path))
    temporary = tmp_path / ".tmp-crash"
    temporary.write_text("partial", encoding="utf-8")
    OutboundTextWal(str(tmp_path))
    assert not temporary.exists()

    unsafe = tmp_path / "unexpected"
    unsafe.write_text("data", encoding="utf-8")
    with pytest.raises(OutboundWalError):
        OutboundTextWal(str(tmp_path))


@pytest.mark.parametrize("settings", [
    {"max_records": True},
    {"max_payload_bytes": True},
    {"max_attempts": True},
    {"lease_seconds": float("nan")},
    {"lease_seconds": float("inf")},
])
def test_settings_reject_boolean_and_nonfinite_values(tmp_path, settings):
    with pytest.raises(ValueError):
        OutboundTextWal(str(tmp_path), **settings)


def test_initialization_fsyncs_parent_and_wal_directory(tmp_path, monkeypatch):
    calls = []
    original = os.fsync

    def record_fsync(descriptor):
        calls.append(descriptor)
        original(descriptor)

    monkeypatch.setattr(os, "fsync", record_fsync)
    OutboundTextWal(str(tmp_path / "wal"))
    assert len(calls) >= 2


def test_stale_claim_cannot_overwrite_new_owner_result(tmp_path):
    wal = OutboundTextWal(str(tmp_path), lease_seconds=5)
    stale = wal.claim("completion-1", PAYLOAD, now=10)
    current = wal.claim("completion-1", PAYLOAD, now=16)

    with pytest.raises(OutboundWalStaleClaimError):
        wal.complete(stale)
    with pytest.raises(OutboundWalStaleClaimError):
        wal.fail(stale, "late failure", now=16)

    wal.complete(current)
    assert wal.claim("completion-1", PAYLOAD, now=20) is None


def test_interrupted_or_transient_manual_redrive_can_resume(tmp_path):
    wal = OutboundTextWal(str(tmp_path), max_attempts=1, lease_seconds=1)
    failed = wal.claim("completion-1", PAYLOAD, now=1)
    assert wal.fail(failed, "timeout", now=1) is True

    # 第一次人工恢复在发起 provider 调用前崩溃，重复调用仍返回同一受保护载荷。
    assert wal.redrive(failed.event_key) == PAYLOAD
    assert wal.redrive(failed.event_key) == PAYLOAD
    retry = wal.claim("completion-1", PAYLOAD, now=2)
    assert wal.fail(retry, "temporary", now=2) is True

    # 人工尝试失败并再次耗尽后仍可显式恢复，不会永久停在无 worker 的 PENDING。
    assert wal.redrive(failed.event_key) == PAYLOAD
