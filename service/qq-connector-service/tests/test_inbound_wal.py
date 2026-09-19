import json
from pathlib import Path
import stat

import pytest

from mytools_qq_connector.inbound_wal import (
    InboundEvent,
    InboundEventWal,
    InboundWalCapacityError,
    InboundWalConflictError,
    InboundWalSecurityError,
    InboundWalStateError,
    STATE_PENDING,
    STATE_PROCESSING,
)


def event(sequence: int = 1, *, session: str = "session-a",
          account: str = "qq-main", content: str = "hello",
          now: float = 100.0) -> InboundEvent:
    return InboundEvent.create(account, session, sequence, {
        "op": 0,
        "s": sequence,
        "t": "C2C_MESSAGE_CREATE",
        "d": {
            "id": f"message-{sequence}",
            "content": content,
            "author": {"user_openid": "sender-private"},
        },
    }, now=now)


def only_record(root: Path, state: str) -> Path:
    records = list((root / state).glob("*.json"))
    assert len(records) == 1
    return records[0]


def test_persists_raw_payload_and_recovers_after_restart(tmp_path):
    root = tmp_path / "inbound-wal"
    first = InboundEventWal(root)
    original = event(content="raw message")

    assert first.store(original) is True
    recovered = InboundEventWal(root).load_batch(100.0, 10)

    assert len(recovered) == 1
    assert recovered[0].payload == original.payload
    assert recovered[0].account_key == "qq-main"
    assert recovered[0].session_id == "session-a"
    assert recovered[0].sequence == 1
    assert recovered[0].state == STATE_PENDING


def test_uses_restricted_permissions_and_rejects_symlink_root(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root)
    wal.store(event())
    wal.save_checkpoint("session-a", 1, "qq-main")

    for directory in (root, root / "pending", root / "done", root / "dead"):
        assert stat.S_IMODE(directory.stat().st_mode) == 0o700
    for path in (root / ".lock", root / "checkpoint.json",
                 only_record(root, "pending")):
        assert stat.S_IMODE(path.stat().st_mode) == 0o600

    target = tmp_path / "target"
    target.mkdir()
    link = tmp_path / "linked-wal"
    link.symlink_to(target, target_is_directory=True)
    with pytest.raises(InboundWalSecurityError, match="symlink"):
        InboundEventWal(link)


def test_initial_layout_fsyncs_existing_parent_then_complete_namespace(
        tmp_path, monkeypatch):
    root = tmp_path / "inbound-wal"
    observations = []
    original_fsync = InboundEventWal._fsync_directory

    def observing_fsync(directory):
        observations.append((Path(directory), tuple(
            (root / name).exists() for name in ("pending", "done", "dead", ".lock"))))
        original_fsync(directory)

    monkeypatch.setattr(
        InboundEventWal, "_fsync_directory", staticmethod(observing_fsync))

    InboundEventWal(root)

    assert observations == [
        (tmp_path, (False, False, False, False)),
        (root, (True, True, True, True)),
    ]

    observations.clear()
    InboundEventWal(root)

    assert observations == [
        (tmp_path, (True, True, True, True)),
        (root, (True, True, True, True)),
    ]


def test_initial_layout_requires_an_existing_parent(tmp_path):
    with pytest.raises(InboundWalSecurityError, match="parent must already exist"):
        InboundEventWal(tmp_path / "missing" / "inbound-wal")


def test_idempotency_key_rejects_conflicting_payload(tmp_path):
    wal = InboundEventWal(tmp_path / "inbound-wal")
    original = event()

    assert wal.store(original) is True
    assert wal.store(event(now=200.0)) is True
    with pytest.raises(InboundWalConflictError, match="conflicting payload"):
        wal.store(event(content="different"))


def test_due_scan_is_ordered_but_deferred_old_event_does_not_block(tmp_path):
    wal = InboundEventWal(tmp_path / "inbound-wal")
    for item in (event(3), event(1), event(2)):
        wal.store(item)
    oldest = [entry for entry in wal.load_batch(100.0, 10)
              if entry.sequence == 1][0]
    wal.reschedule(oldest, 200.0, "HTTP_503", 5)

    assert [entry.sequence for entry in wal.load_batch(150.0, 10)] == [2, 3]
    assert [entry.sequence for entry in wal.load_batch(200.0, 10)] == [1, 2, 3]


def test_claim_lease_prevents_duplicate_work_and_recovers_after_expiry(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root)
    wal.store(event())

    claimed = wal.claim_batch(100.0, 1, lease_seconds=30.0)

    assert claimed[0].state == STATE_PROCESSING
    assert claimed[0].lease_until == 130.0
    assert wal.claim_batch(129.999, 1, lease_seconds=30.0) == []
    recovered = InboundEventWal(root).claim_batch(130.0, 1, lease_seconds=30.0)
    assert recovered[0].revision == claimed[0].revision + 1
    assert recovered[0].attempt == 1
    assert recovered[0].lease_until == 160.0


def test_repeated_worker_crash_exhausts_lease_retry_budget(tmp_path):
    wal = InboundEventWal(tmp_path / "inbound-wal")
    wal.store(event())
    first = wal.claim_batch(100.0, 1, lease_seconds=10.0, max_attempts=1)

    assert len(first) == 1
    assert wal.claim_batch(110.0, 1, lease_seconds=10.0, max_attempts=1) == []
    assert wal.stats()["processing"] == 0
    assert wal.stats()["dead"] == 1
    document = json.loads(only_record(wal.root, "dead").read_text(encoding="utf-8"))
    assert document["lastErrorCode"] == "WORKER_LEASE_EXPIRED"


def test_retry_exhaustion_survives_restart_and_can_be_manually_redriven(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root, clock=lambda: 300.0)
    wal.store(event(content="super-secret-message"))
    current = wal.load_batch(100.0, 1)[0]

    current = wal.reschedule(current, 110.0, "HTTP_503", 2)
    assert current is not None
    assert current.attempt == 1
    assert current.next_attempt_at == 110.0
    assert wal.reschedule(current, 120.0, "HTTP_503", 2) is None

    assert wal.stats() == {
        "pending": 0, "processing": 0, "done": 0, "dead": 1,
        "payloadBytes": event(content="super-secret-message").payload_bytes,
    }
    raw = only_record(root, "dead").read_text(encoding="utf-8")
    document = json.loads(raw)
    assert document["kind"] == "INBOUND_RETRY_DEAD"
    assert document["attempt"] == 2
    assert document["lastErrorCode"] == "HTTP_503"
    assert document["payload"]["d"]["content"] == "super-secret-message"

    restarted = InboundEventWal(root, clock=lambda: 400.0)
    restored = restarted.redrive(current.event_key, now=410.0)
    assert restored.attempt == 0
    assert restored.last_error_code is None
    assert restored.lease_until is None
    assert restored.next_attempt_at == 410.0
    assert restored.payload["d"]["content"] == "super-secret-message"
    assert restarted.stats()["dead"] == 0
    assert restarted.load_batch(410.0, 1) == [restored]


def test_permanent_dead_is_sanitized_and_cannot_be_redriven(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root, clock=lambda: 300.0)
    wal.store(event(content="permanent-secret"))
    current = wal.load_batch(100.0, 1)[0]

    wal.mark_dead(current, "HTTP_400")

    raw = only_record(root, "dead").read_text(encoding="utf-8")
    document = json.loads(raw)
    assert document["kind"] == "INBOUND_DEAD"
    assert "payload" not in document
    assert "sessionId" not in document
    assert "permanent-secret" not in raw
    assert "sender-private" not in raw
    with pytest.raises(InboundWalStateError, match="permanent"):
        wal.redrive(current.event_key)


def test_redrive_commit_recovers_if_dead_unlink_is_interrupted(
        tmp_path, monkeypatch):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root)
    wal.store(event(content="recover-after-crash"))
    entry = wal.load_batch(100.0, 1)[0]
    assert wal.reschedule(entry, 101.0, "HTTP_503", 1) is None
    real_unlink = wal._unlink_record

    def interrupted_unlink(path):
        if path.parent.name == "dead":
            raise OSError("simulated redrive crash")
        real_unlink(path)

    monkeypatch.setattr(wal, "_unlink_record", interrupted_unlink)
    with pytest.raises(OSError, match="simulated redrive crash"):
        wal.redrive(entry.event_key, now=200.0)

    recovered = InboundEventWal(root)
    pending = recovered.load_batch(200.0, 1)
    assert len(pending) == 1
    assert pending[0].attempt == 0
    assert pending[0].payload["d"]["content"] == "recover-after-crash"
    assert recovered.stats()["dead"] == 0


def test_recoverable_dead_payload_counts_toward_total_capacity(tmp_path):
    first = event(1)
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(
        root, max_payload_bytes=first.payload_bytes,
        max_total_payload_bytes=first.payload_bytes)
    wal.store(first)
    entry = wal.load_batch(100.0, 1)[0]
    assert wal.reschedule(entry, 101.0, "HTTP_503", 1) is None

    with pytest.raises(InboundWalCapacityError, match="byte limit"):
        wal.store(event(2))


def test_redrive_preserves_dead_when_pending_capacity_is_full(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root, max_pending_records=1)
    first = event(1)
    wal.store(first)
    first_entry = wal.load_batch(100.0, 1)[0]
    assert wal.reschedule(first_entry, 101.0, "HTTP_503", 1) is None
    wal.store(event(2))

    with pytest.raises(InboundWalCapacityError, match="record limit"):
        wal.redrive(first_entry.event_key, now=102.0)

    assert wal.stats()["pending"] == 1
    assert wal.stats()["dead"] == 1
    assert (root / "dead" / f"{first_entry.event_key}.json").exists()


def test_terminal_heap_ignores_stale_same_key_generation(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root, max_dead_records=2)
    first = event(1)
    wal.store(first)
    first_entry = wal.load_batch(100.0, 1)[0]
    assert wal.reschedule(first_entry, 101.0, "HTTP_503", 1) is None
    restored = wal.redrive(first_entry.event_key, now=102.0)

    second = event(2)
    wal.store(second)
    second_entry = [item for item in wal.load_batch(102.0, 10)
                    if item.sequence == 2][0]
    wal.mark_dead(second_entry, "HTTP_400")
    assert wal.reschedule(restored, 103.0, "HTTP_503", 1) is None

    third = event(3)
    wal.store(third)
    third_entry = [item for item in wal.load_batch(103.0, 10)
                   if item.sequence == 3][0]
    wal.mark_dead(third_entry, "HTTP_400")

    assert (root / "dead" / f"{first_entry.event_key}.json").exists()
    assert not (root / "dead" / f"{second_entry.event_key}.json").exists()
    assert wal.stats()["dead"] == 2


def test_hot_store_claim_and_stats_do_not_rebuild_or_scan_backlog(
        tmp_path, monkeypatch):
    wal = InboundEventWal(tmp_path / "inbound-wal", max_pending_records=1000)
    rebuilds = 0
    reads = 0
    original_rebuild = wal._rebuild_hot_index
    original_read = wal._read_entry

    def counted_rebuild():
        nonlocal rebuilds
        rebuilds += 1
        return original_rebuild()

    def counted_read(path):
        nonlocal reads
        reads += 1
        return original_read(path)

    monkeypatch.setattr(wal, "_rebuild_hot_index", counted_rebuild)
    monkeypatch.setattr(wal, "_read_entry", counted_read)
    for sequence in range(1, 501):
        wal.store(event(sequence))

    assert wal.stats()["pending"] == 500
    assert wal.stats()["payloadBytes"] > 0
    assert rebuilds == 0
    assert reads == 0

    claimed = wal.claim_batch(100.0, 4)
    assert [item.sequence for item in claimed] == [1, 2, 3, 4]
    assert reads == 4
    assert rebuilds == 0


def test_claim_write_failure_rebuilds_index_and_event_remains_claimable(
        tmp_path, monkeypatch):
    wal = InboundEventWal(tmp_path / "inbound-wal")
    wal.store(event())
    original_write = wal._atomic_write
    failed = False

    def fail_once(directory, name, document):
        nonlocal failed
        if directory.name == "pending" and not failed:
            failed = True
            raise OSError("simulated ENOSPC")
        return original_write(directory, name, document)

    monkeypatch.setattr(wal, "_atomic_write", fail_once)
    with pytest.raises(OSError, match="ENOSPC"):
        wal.claim_batch(100.0, 1)

    claimed = wal.claim_batch(100.0, 1)
    assert len(claimed) == 1
    assert claimed[0].sequence == 1


def test_pending_entry_already_at_budget_becomes_recoverable_dead(
        tmp_path, monkeypatch):
    wal = InboundEventWal(tmp_path / "inbound-wal")
    wal.store(event(content="budget-crash"))
    entry = wal.load_batch(100.0, 1)[0]
    original_terminalize = wal._terminalize_retry_dead

    def interrupt_terminalize(_entry, _error_code):
        raise OSError("simulated terminal crash")

    monkeypatch.setattr(wal, "_terminalize_retry_dead", interrupt_terminalize)
    with pytest.raises(OSError, match="terminal crash"):
        wal.reschedule(entry, 101.0, "HTTP_503", 1)
    monkeypatch.setattr(wal, "_terminalize_retry_dead", original_terminalize)

    assert wal.claim_batch(101.0, 1, max_attempts=1) == []
    document = json.loads(only_record(wal.root, "dead").read_text(encoding="utf-8"))
    assert document["kind"] == "INBOUND_RETRY_DEAD"
    restored = wal.redrive(entry.event_key, 102.0)
    assert restored.payload["d"]["content"] == "budget-crash"


def test_retry_dead_capacity_never_prunes_recoverable_payload(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root, max_dead_records=1)
    first = event(1, content="first-recoverable")
    wal.store(first)
    first_entry = wal.load_batch(100.0, 1)[0]
    assert wal.reschedule(first_entry, 101.0, "HTTP_503", 1) is None

    second = event(2, content="second-recoverable")
    wal.store(second)
    second_entry = wal.load_batch(100.0, 1)[0]
    with pytest.raises(InboundWalCapacityError, match="recoverable dead"):
        wal.reschedule(second_entry, 101.0, "HTTP_503", 1)

    first_dead = root / "dead" / f"{first_entry.event_key}.json"
    second_pending = root / "pending" / f"{second_entry.event_key}.json"
    assert json.loads(first_dead.read_text(encoding="utf-8"))["payload"][
        "d"]["content"] == "first-recoverable"
    assert json.loads(second_pending.read_text(encoding="utf-8"))["payload"][
        "d"]["content"] == "second-recoverable"
    assert wal.stats()["dead"] == 1
    assert wal.stats()["pending"] == 1


def test_complete_is_idempotent_and_replay_is_not_reexecuted(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root)
    original = event()
    wal.store(original)
    entry = wal.load_batch(100.0, 1)[0]

    wal.complete(entry)
    wal.complete(entry)

    assert wal.store(original) is False
    assert wal.stats()["done"] == 1
    assert not list((root / "pending").glob("*.json"))
    document = json.loads(only_record(root, "done").read_text(encoding="utf-8"))
    assert "payload" not in document
    assert "sessionId" not in document


def test_terminal_records_are_bounded_and_checkpoint_covers_pruned_replay(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root, max_done_records=2, max_dead_records=2)
    for sequence in range(1, 5):
        item = event(sequence)
        wal.store(item)
        wal.complete([entry for entry in wal.load_batch(100.0, 10)
                      if entry.sequence == sequence][0])
    wal.save_checkpoint("session-a", 4, "qq-main")

    assert wal.stats()["done"] == 2
    assert wal.store(event(1)) is False


def test_checkpoint_is_atomic_monotonic_and_invalidatable(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root, clock=lambda: 500.0)

    first = wal.save_checkpoint("session-a", 10, "qq-main")
    same = wal.save_checkpoint("session-a", 10, "qq-main")
    assert same == first
    with pytest.raises(InboundWalConflictError, match="backwards"):
        wal.save_checkpoint("session-a", 9, "qq-main")

    replacement = wal.save_checkpoint("session-b", 1, "qq-main")
    assert replacement.session_id == "session-b"
    assert InboundEventWal(root).load_checkpoint() == replacement
    assert wal.invalidate_checkpoint("session-a") is False
    assert wal.invalidate_checkpoint("session-b") is True
    assert wal.load_checkpoint() is None
    assert wal.invalidate_checkpoint() is False


def test_checkpoint_symlink_is_rejected_without_touching_target(tmp_path):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root)
    target = tmp_path / "outside-checkpoint.json"
    target.write_text("outside-secret", encoding="utf-8")
    (root / "checkpoint.json").symlink_to(target)

    with pytest.raises(InboundWalSecurityError, match="checkpoint"):
        wal.load_checkpoint()
    with pytest.raises(InboundWalSecurityError):
        wal.save_checkpoint("session-a", 1)
    assert target.read_text(encoding="utf-8") == "outside-secret"


def test_capacity_bounds_individual_total_payload_and_record_count(tmp_path):
    with pytest.raises(ValueError, match="maximum_payload_bytes"):
        InboundEvent.create("qq-main", "session-a", 1,
                            {"body": "x" * 200}, now=1.0,
                            maximum_payload_bytes=50)

    bytes_per_event = event(1).payload_bytes
    wal = InboundEventWal(tmp_path / "bytes-wal", max_pending_records=10,
                          max_payload_bytes=bytes_per_event,
                          max_total_payload_bytes=bytes_per_event)
    wal.store(event(1))
    with pytest.raises(InboundWalCapacityError, match="byte limit"):
        wal.store(event(2))

    count_wal = InboundEventWal(tmp_path / "count-wal", max_pending_records=1)
    count_wal.store(event(1))
    with pytest.raises(InboundWalCapacityError, match="record limit"):
        count_wal.store(event(2))


@pytest.mark.parametrize("mutation", ["json", "permission", "tamper", "symlink"])
def test_corrupt_or_unsafe_pending_is_quarantined_without_reading_target(
        tmp_path, mutation):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root)
    wal.store(event(content="private-body"))
    record = only_record(root, "pending")
    outside = tmp_path / "outside.json"
    outside.write_text("outside-secret", encoding="utf-8")

    if mutation == "json":
        record.write_text("not-json", encoding="utf-8")
        record.chmod(0o600)
    elif mutation == "permission":
        record.chmod(0o644)
    elif mutation == "tamper":
        document = json.loads(record.read_text(encoding="utf-8"))
        document["payload"]["d"]["content"] = "tampered"
        record.write_text(json.dumps(document), encoding="utf-8")
        record.chmod(0o600)
    else:
        record.unlink()
        record.symlink_to(outside)

    recovered = InboundEventWal(root)

    assert recovered.load_batch(1_000.0, 10) == []
    assert recovered.stats()["dead"] == 1
    assert outside.read_text(encoding="utf-8") == "outside-secret"
    assert "private-body" not in only_record(root, "dead").read_text(encoding="utf-8")
    # Gateway 按同一 session/seq 重放时，隔离摘要应稳定吸收事件，
    # 而非永久卡住游标。
    assert recovered.store(event(content="private-body")) is False


def test_recovers_terminal_commit_if_crash_happens_before_raw_unlink(
        tmp_path, monkeypatch):
    root = tmp_path / "inbound-wal"
    wal = InboundEventWal(root)
    original = event()
    wal.store(original)
    entry = wal.load_batch(100.0, 1)[0]
    real_unlink = wal._unlink_record

    def interrupted_unlink(path):
        if path.parent.name == "pending":
            raise OSError("simulated crash before unlink")
        real_unlink(path)

    monkeypatch.setattr(wal, "_unlink_record", interrupted_unlink)
    with pytest.raises(OSError, match="simulated crash"):
        wal.complete(entry)

    recovered = InboundEventWal(root)
    assert recovered.load_batch(100.0, 10) == []
    assert recovered.store(original) is False
    assert recovered.stats()["done"] == 1


def test_stale_revision_cannot_overwrite_newer_worker_state(tmp_path):
    wal = InboundEventWal(tmp_path / "inbound-wal")
    wal.store(event())
    stale = wal.load_batch(100.0, 1)[0]
    claimed = wal.claim_batch(100.0, 1, 30.0)[0]

    with pytest.raises(InboundWalStateError, match="stale"):
        wal.reschedule(stale, 120.0, "HTTP_503", 3)

    updated = wal.reschedule(claimed, 120.0, "HTTP_503", 3)
    assert updated is not None
    assert updated.state == STATE_PENDING


def test_error_code_and_numeric_boundaries_reject_unsafe_values(tmp_path):
    wal = InboundEventWal(tmp_path / "inbound-wal")
    wal.store(event())
    entry = wal.load_batch(100.0, 1)[0]

    with pytest.raises(TypeError, match="string"):
        wal.mark_dead(entry, 503)  # type: ignore[arg-type]
    with pytest.raises(ValueError, match="unsafe"):
        wal.mark_dead(entry, "secret: database unavailable")
    with pytest.raises(ValueError, match="sequence"):
        InboundEvent.create("qq-main", "session-a", -1, {})
