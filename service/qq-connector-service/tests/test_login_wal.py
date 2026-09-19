import json
import hashlib
from pathlib import Path
import stat

import pytest

from mytools_qq_connector.login_wal import (
    LoginCommand,
    LoginCommandWal,
    LoginWalConflictError,
    LoginWalSecurityError,
    LoginWalStateError,
    PHASE_EXECUTE,
    PHASE_FAILURE_REPLY,
)


def command(message_id: str = "message-1", sender_id: str = "sender-1",
            now: float = 100.0, payload: object | None = None) -> LoginCommand:
    body = {"command": "LOGIN"} if payload is None else payload
    return LoginCommand.create("qq-main", message_id, sender_id, 180.0, now,
                               LoginCommand.digest(body))


def only_record(root: Path, state: str) -> Path:
    records = list((root / state).glob("*.json"))
    assert len(records) == 1
    return records[0]


def test_persists_and_recovers_due_entry_after_restart(tmp_path):
    root = tmp_path / "login-wal"
    first = LoginCommandWal(root)
    assert first.store(command()) is True

    recovered = LoginCommandWal(root).load_batch(100.0, 10)

    assert len(recovered) == 1
    assert recovered[0].message_id == "message-1"
    assert recovered[0].phase == PHASE_EXECUTE
    assert recovered[0].attempt == 0
    assert recovered[0].next_attempt_at == 100.0


def test_creates_restricted_directories_and_files(tmp_path):
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root)
    wal.store(command())

    for directory in (root, root / "pending", root / "done", root / "dead"):
        assert stat.S_IMODE(directory.stat().st_mode) == 0o700
    assert stat.S_IMODE((root / ".lock").stat().st_mode) == 0o600
    assert stat.S_IMODE(only_record(root, "pending").stat().st_mode) == 0o600


def test_rejects_relative_or_symlink_root(tmp_path):
    with pytest.raises(ValueError, match="absolute"):
        LoginCommandWal("relative/login-wal")

    target = tmp_path / "target"
    target.mkdir()
    link = tmp_path / "link"
    link.symlink_to(target, target_is_directory=True)
    with pytest.raises(LoginWalSecurityError, match="symlink"):
        LoginCommandWal(link)


def test_initial_layout_fsyncs_existing_parent_then_complete_namespace(
        tmp_path, monkeypatch):
    root = tmp_path / "login-wal"
    observations = []
    original_fsync = LoginCommandWal._fsync_directory

    def observing_fsync(directory):
        observations.append((Path(directory), tuple(
            (root / name).exists()
            for name in ("pending", "done", "dead", "rejected", ".lock"))))
        original_fsync(directory)

    monkeypatch.setattr(
        LoginCommandWal, "_fsync_directory", staticmethod(observing_fsync))

    LoginCommandWal(root)

    assert observations == [
        (tmp_path, (False, False, False, False, False)),
        (root, (True, True, True, True, True)),
    ]

    observations.clear()
    LoginCommandWal(root)

    assert observations == [
        (tmp_path, (True, True, True, True, True)),
        (root, (True, True, True, True, True)),
    ]


def test_initial_layout_requires_an_existing_parent(tmp_path):
    with pytest.raises(LoginWalSecurityError, match="parent must already exist"):
        LoginCommandWal(tmp_path / "missing" / "login-wal")


def test_idempotency_validates_sender_and_payload_digest(tmp_path):
    wal = LoginCommandWal(tmp_path / "login-wal")
    original = command()
    assert wal.store(original) is True
    assert wal.store(original) is True

    with pytest.raises(LoginWalConflictError, match="sender or payload"):
        wal.store(command(sender_id="sender-2"))
    with pytest.raises(LoginWalConflictError, match="sender or payload"):
        wal.store(command(payload={"command": "LOGIN", "nonce": 2}))


def test_reschedule_applies_backoff_and_increments_attempt(tmp_path):
    wal = LoginCommandWal(tmp_path / "login-wal")
    wal.store(command())
    entry = wal.load_batch(100.0, 1)[0]

    delayed = wal.reschedule(entry, 130.0, "SCHEDULER_TIMEOUT")

    assert delayed.attempt == 1
    assert delayed.next_attempt_at == 130.0
    assert delayed.last_error_code == "SCHEDULER_TIMEOUT"
    assert wal.load_batch(129.999, 1) == []
    assert wal.load_batch(130.0, 1) == [delayed]
    with pytest.raises(ValueError, match="unsafe"):
        wal.reschedule(delayed, 140.0, "secret: internal exception")


def test_attach_task_and_failure_reply_are_durable_phases(tmp_path):
    now = [200.0]
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root, clock=lambda: now[0])
    wal.store(command(now=100.0))
    entry = wal.load_batch(100.0, 1)[0]

    attached = wal.attach_task(entry, "task-123")
    failed = wal.reschedule(attached, 150.0, "TASK_PENDING")
    failure_reply = wal.begin_failure_reply(failed, "TASK_FAILED")

    assert failure_reply.phase == PHASE_FAILURE_REPLY
    assert failure_reply.task_instance_id == "task-123"
    assert failure_reply.attempt == 0
    assert failure_reply.next_attempt_at == 200.0
    assert LoginCommandWal(root).load_batch(200.0, 1) == [failure_reply]


def test_failure_reply_can_begin_before_scheduler_task_exists(tmp_path):
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root, clock=lambda: 120.0)
    wal.store(command())
    entry = wal.load_batch(100.0, 1)[0]

    failure_reply = wal.begin_failure_reply(entry, "SCHEDULER_CREATE_FAILED")

    assert failure_reply.phase == PHASE_FAILURE_REPLY
    assert failure_reply.task_instance_id is None
    assert LoginCommandWal(root).load_batch(120.0, 1) == [failure_reply]


def test_complete_moves_tombstone_and_prevents_reexecution(tmp_path):
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root)
    original = command()
    wal.store(original)
    entry = wal.load_batch(100.0, 1)[0]

    wal.complete(entry)
    wal.complete(entry)

    assert wal.stats() == {"pending": 0, "done": 1, "dead": 0}
    assert wal.store(original) is False
    assert stat.S_IMODE(only_record(root, "done").stat().st_mode) == 0o600


@pytest.mark.parametrize("mutation", ["json", "permission", "tamper", "symlink"])
def test_invalid_pending_record_is_isolated_without_blocking_later_events(tmp_path, mutation):
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root)
    wal.store(command())
    record = only_record(root, "pending")

    if mutation == "json":
        record.write_text("not-json", encoding="utf-8")
        record.chmod(0o600)
    elif mutation == "permission":
        record.chmod(0o644)
    elif mutation == "tamper":
        document = json.loads(record.read_text(encoding="utf-8"))
        document["senderId"] = "tampered"
        record.write_text(json.dumps(document), encoding="utf-8")
        record.chmod(0o600)
    else:
        record.unlink()
        target = tmp_path / "outside.json"
        target.write_text("private", encoding="utf-8")
        record.symlink_to(target)

    assert wal.load_batch(1_000.0, 10) == []
    assert wal.stats() == {"pending": 0, "done": 0, "dead": 1}
    assert wal.store(command(message_id="message-2", now=110.0)) is True
    assert [entry.message_id for entry in wal.load_batch(110.0, 10)] == ["message-2"]
    if mutation == "symlink":
        assert (tmp_path / "outside.json").read_text(encoding="utf-8") == "private"


def test_dead_tombstone_does_not_block_a_later_event(tmp_path):
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root)
    first = command()
    wal.store(first)
    entry = wal.load_batch(100.0, 1)[0]

    wal.mark_dead(entry, "DEADLINE_EXCEEDED")

    assert wal.store(first) is False
    assert wal.store(command(message_id="message-2", now=101.0)) is True
    assert [item.message_id for item in wal.load_batch(101.0, 10)] == ["message-2"]
    assert wal.stats() == {"pending": 1, "done": 0, "dead": 1}


def test_error_codes_are_strings_and_bounded(tmp_path):
    wal = LoginCommandWal(tmp_path / "login-wal")
    wal.store(command())
    entry = wal.load_batch(100.0, 1)[0]

    with pytest.raises(TypeError, match="string"):
        wal.mark_dead(entry, 500)  # type: ignore[arg-type]
    with pytest.raises(ValueError, match="too long"):
        wal.mark_dead(entry, "X" * 65)


def test_rejected_event_audit_is_idempotent_safe_and_bounded(tmp_path):
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root, clock=lambda: 200.0)

    wal.record_rejected_event("qq-main", "C2C_MESSAGE_CREATE", "message-1", 1,
                              "INVALID_MESSAGE_STRUCTURE", 2)
    wal.record_rejected_event("qq-main", "C2C_MESSAGE_CREATE", "message-1", 1,
                              "INVALID_MESSAGE_STRUCTURE", 2)
    wal.record_rejected_event("qq-main", "C2C_MESSAGE_CREATE", "message-2", 2,
                              "HTTP_400", 2)
    wal.record_rejected_event("qq-main", "C2C_MESSAGE_CREATE", "message-3", 3,
                              "HTTP_422", 2)

    assert wal.rejected_count() == 2
    documents = [json.loads(path.read_text(encoding="utf-8"))
                 for path in (root / "rejected").glob("*.json")]
    assert all("senderId" not in document and "body" not in document
               for document in documents)
    assert all(len(document["messageIdDigest"]) == 64 for document in documents)


def test_done_and_dead_retention_keep_only_newest_records(tmp_path):
    now = [200.0]
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root, clock=lambda: now[0],
                          max_done_records=2, max_dead_records=2)
    for index in range(3):
        current = command(message_id=f"done-{index}", now=100.0 + index)
        wal.store(current)
        wal.complete(wal.load_batch(200.0, 10)[0])
        now[0] += 1
    for index in range(3):
        current = command(message_id=f"dead-{index}", now=110.0 + index)
        wal.store(current)
        entry = next(item for item in wal.load_batch(200.0, 10)
                     if item.message_id == current.message_id)
        wal.mark_dead(entry, "LOGIN_FAILED")
        now[0] += 1

    assert wal.stats() == {"pending": 0, "done": 2, "dead": 2}
    assert wal.store(command(message_id="done-2", now=102.0)) is False
    assert wal.store(command(message_id="dead-2", now=112.0)) is False


def test_dead_redrive_and_ack_are_exact_idempotent_and_private(tmp_path):
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root, clock=lambda: 300.0)
    wal.store(command())
    wal.mark_dead(wal.load_batch(100.0, 1)[0], "LOGIN_FAILED")
    event_key = only_record(root, "dead").stem

    restored = wal.redrive(event_key)
    duplicate = wal.redrive(event_key)

    assert restored == duplicate
    assert restored.phase == PHASE_EXECUTE
    assert restored.task_instance_id is None
    assert restored.attempt == 0
    assert restored.last_error_code is None
    assert restored.created_at == 300.0
    assert restored.generation == 1
    assert wal.stats() == {"pending": 1, "done": 0, "dead": 0}

    wal.mark_dead(restored, "LOGIN_FAILED")
    wal.acknowledge_dead(event_key)
    wal.acknowledge_dead(event_key)
    assert wal.stats() == {"pending": 0, "done": 1, "dead": 0}
    document = json.loads(only_record(root, "done").read_text(encoding="utf-8"))
    assert "body" not in document

    with pytest.raises(ValueError, match="event_key"):
        wal.redrive("../private")
    with pytest.raises(LoginWalStateError, match="does not exist"):
        wal.redrive("f" * 64)


@pytest.mark.parametrize("name,value", [
    ("max_done_records", 0), ("max_dead_records", -1),
])
def test_terminal_limits_must_be_positive(tmp_path, name, value):
    settings = {name: value}
    with pytest.raises(ValueError, match="positive bounded"):
        LoginCommandWal(tmp_path / name, **settings)


def test_generation_survives_updates_restart_and_attach_ack_loss(tmp_path):
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root, clock=lambda: 200.0)
    wal.store(command())
    initial = wal.load_batch(100.0, 1)[0]
    assert initial.generation == 0

    attached = wal.attach_task(initial, "task-1")
    duplicate_attach = wal.attach_task(attached, "task-1")
    delayed = wal.reschedule(duplicate_attach, 150.0, "TASK_PENDING")
    failed = wal.begin_failure_reply(delayed, "TASK_FAILED")

    assert duplicate_attach.generation == 0
    assert delayed.generation == 0
    assert failed.generation == 0
    assert LoginCommandWal(root).load_batch(200.0, 1)[0].generation == 0


def test_legacy_record_without_generation_loads_as_generation_zero(tmp_path):
    root = tmp_path / "login-wal"
    wal = LoginCommandWal(root)
    wal.store(command())
    record = only_record(root, "pending")
    document = json.loads(record.read_text(encoding="utf-8"))
    document.pop("generation")
    document.pop("recordDigest")
    canonical = json.dumps(document, ensure_ascii=False, sort_keys=True,
                           separators=(",", ":"), allow_nan=False).encode("utf-8")
    document["recordDigest"] = hashlib.sha256(canonical).hexdigest()
    record.write_text(json.dumps(document, ensure_ascii=False, sort_keys=True,
                                 separators=(",", ":")) + "\n", encoding="utf-8")
    record.chmod(0o600)

    recovered = LoginCommandWal(root).load_batch(100.0, 1)[0]

    assert recovered.generation == 0
