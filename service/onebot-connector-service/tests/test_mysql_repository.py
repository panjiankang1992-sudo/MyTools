from datetime import UTC, datetime, timedelta
import json

from pymysql.err import IntegrityError
import pytest

from mytools_onebot_connector.models import InboundEvent, InboundEventStatus, TextReply
from mytools_onebot_connector.mysql_repository import MySqlAccountRepository, map_inbound_event


class DuplicateCursor:
    def __init__(self, row):
        self._row = row
        self._selected = False

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def execute(self, statement, _parameters):
        normalized = " ".join(statement.split())
        if normalized.startswith("INSERT INTO onebot_text_reply"):
            raise IntegrityError(1062, "duplicate unique key")
        if "WHERE account_key=%s AND idempotency_key=%s" in normalized:
            self._selected = True

    def fetchone(self):
        return self._row if self._selected else None


class DuplicateConnection:
    def __init__(self, row):
        self._row = row
        self.rollbacks = 0
        self.closed = False

    def cursor(self):
        return DuplicateCursor(self._row)

    def commit(self):
        raise AssertionError("duplicate claims must not commit")

    def rollback(self):
        self.rollbacks += 1

    def close(self):
        self.closed = True


def as_row(reply):
    return {"id": str(reply.id), "account_key": reply.account_key,
            "idempotency_key": reply.idempotency_key, "message_type": reply.message_type,
            "target_id": reply.target_id, "message_id": reply.message_id,
            "text_digest": reply.text_digest, "status": reply.status.value,
            "attempt_count": reply.attempt_count,
            "attempt_token": None if reply.attempt_token is None else str(reply.attempt_token),
            "next_attempt_at": reply.next_attempt_at.replace(tzinfo=None),
            "provider_message_id": reply.provider_message_id,
            "last_error_code": reply.last_error_code,
            "created_at": reply.created_at.replace(tzinfo=None),
            "updated_at": reply.updated_at.replace(tzinfo=None)}


def test_mysql_concurrent_unique_conflict_returns_equivalent_existing_claim():
    candidate = TextReply.create("qq_primary", "reply-7", "private", "123", "7", "done")
    connection = DuplicateConnection(as_row(candidate))
    repository = MySqlAccountRepository(lambda: connection)

    claim = repository.claim_text_reply(candidate)

    assert claim.claimed is False
    assert claim.reply.matches(candidate)
    assert connection.rollbacks == 1
    assert connection.closed is True


def test_mysql_concurrent_unique_conflict_rejects_different_payload():
    existing = TextReply.create("qq_primary", "reply-7", "private", "123", "7", "done")
    candidate = TextReply.create("qq_primary", "reply-7", "private", "124", "7", "done")
    connection = DuplicateConnection(as_row(existing))
    repository = MySqlAccountRepository(lambda: connection)

    with pytest.raises(ValueError, match="idempotency conflict"):
        repository.claim_text_reply(candidate)

    assert connection.rollbacks == 1
    assert connection.closed is True


def as_inbound_row(event):
    """把入站事件转换成字典游标记录。"""
    return {"id": str(event.id), "account_key": event.account_key,
            "event_key": event.event_key, "source_message_id": event.source_message_id,
            "payload_json": event.payload_json, "payload_digest": event.payload_digest,
            "status": event.status.value, "attempt_count": event.attempt_count,
            "next_attempt_at": event.next_attempt_at.replace(tzinfo=None),
            "attempt_token": None if event.attempt_token is None else str(event.attempt_token),
            "lease_until": None if event.lease_until is None
            else event.lease_until.replace(tzinfo=None),
            "last_error_code": event.last_error_code,
            "delivered_at": None if event.delivered_at is None
            else event.delivered_at.replace(tzinfo=None),
            "dead_at": None if event.dead_at is None else event.dead_at.replace(tzinfo=None),
            "created_at": event.created_at.replace(tzinfo=None),
            "updated_at": event.updated_at.replace(tzinfo=None)}


class InboundDuplicateCursor:
    """模拟入站事件唯一键冲突后的读取。"""

    def __init__(self, row):
        self._row = row
        self._selected = False

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def execute(self, statement, _parameters):
        normalized = " ".join(statement.split())
        if normalized.startswith("INSERT INTO onebot_inbound_event"):
            raise IntegrityError(1062, "duplicate unique key")
        if "WHERE account_key=%s AND event_key=%s" in normalized:
            self._selected = True

    def fetchone(self):
        return self._row if self._selected else None


class InboundDuplicateConnection(DuplicateConnection):
    """为入站事件冲突提供独立游标。"""

    def cursor(self):
        return InboundDuplicateCursor(self._row)


def test_mysql_inbound_duplicate_returns_equivalent_existing_event():
    """数据库唯一键冲突后应返回完全相同的已有事件。"""
    candidate = InboundEvent.create(
        "qq_primary", "a" * 64, "7", {"event": {"message_id": 7}})
    connection = InboundDuplicateConnection(as_inbound_row(candidate))
    repository = MySqlAccountRepository(lambda: connection)

    claim = repository.claim_inbound_event(candidate)

    assert claim.claimed is False
    assert claim.event.matches(candidate)
    assert connection.rollbacks == 1
    assert connection.closed is True


def test_mysql_inbound_duplicate_rejects_changed_payload():
    """数据库唯一键冲突不得用不同载荷覆盖已有事件。"""
    existing = InboundEvent.create(
        "qq_primary", "a" * 64, "7", {"event": {"message_id": 7}})
    candidate = InboundEvent.create(
        "qq_primary", "a" * 64, "7", {"event": {"message_id": 7, "text": "changed"}})
    connection = InboundDuplicateConnection(as_inbound_row(existing))
    repository = MySqlAccountRepository(lambda: connection)

    with pytest.raises(ValueError, match="idempotency conflict"):
        repository.claim_inbound_event(candidate)

    assert connection.rollbacks == 1
    assert connection.closed is True


def test_mysql_json_normalization_does_not_break_inbound_idempotency():
    """MySQL 重新格式化 JSON 文本后仍应通过摘要判断为同一事件。"""
    candidate = InboundEvent.create(
        "qq_primary", "a" * 64, "7",
        {"ownerId": 7, "event": {"message_id": 7, "text": "same"}})
    row = as_inbound_row(candidate)
    row["payload_json"] = json.dumps(json.loads(candidate.payload_json), indent=2)

    mapped = map_inbound_event(row)

    assert mapped.matches(candidate)


class ClaimDueCursor:
    """模拟一次 SELECT FOR UPDATE、CAS 更新和结果读取。"""

    def __init__(self, row):
        self._row = row
        self._mode = ""
        self.rowcount = 0

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def execute(self, statement, parameters):
        normalized = " ".join(statement.split())
        if normalized.startswith("SELECT id FROM onebot_inbound_event"):
            assert "FOR UPDATE SKIP LOCKED" in normalized
            self._mode = "ids"
            self.rowcount = 1
        elif "SET status='DEAD'" in normalized:
            self._mode = "expired"
            self.rowcount = 0
        elif normalized.startswith("UPDATE onebot_inbound_event"):
            token, lease_until, updated_at = parameters[:3]
            self._row.update({"status": "IN_FLIGHT",
                              "attempt_count": self._row["attempt_count"] + 1,
                              "attempt_token": token, "lease_until": lease_until,
                              "last_error_code": None, "updated_at": updated_at})
            self._mode = "updated"
            self.rowcount = 1
        elif normalized.startswith("SELECT * FROM onebot_inbound_event WHERE id=%s"):
            self._mode = "event"
            self.rowcount = 1

    def fetchall(self):
        return [{"id": self._row["id"]}] if self._mode == "ids" else []

    def fetchone(self):
        return self._row if self._mode == "event" else None


class ClaimDueConnection:
    """记录到期事件申领事务。"""

    def __init__(self, row):
        self._cursor = ClaimDueCursor(row)
        self.commits = 0
        self.rollbacks = 0
        self.closed = False

    def cursor(self):
        return self._cursor

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1

    def close(self):
        self.closed = True


def test_mysql_claims_due_event_with_lease_and_fencing_token():
    """到期扫描应在单个事务中增加次数并写入唯一尝试令牌。"""
    event = InboundEvent.create(
        "qq_primary", "a" * 64, "7", {"event": {"message_id": 7}})
    connection = ClaimDueConnection(as_inbound_row(event))
    repository = MySqlAccountRepository(lambda: connection)
    now = datetime.now(UTC) + timedelta(seconds=1)

    claimed = repository.claim_due_inbound_events(
        now, now + timedelta(seconds=30), 8, 9)

    assert len(claimed) == 1
    assert claimed[0].status is InboundEventStatus.IN_FLIGHT
    assert claimed[0].attempt_count == 1
    assert claimed[0].attempt_token is not None
    assert connection.commits == 1
    assert connection.rollbacks == 0
    assert connection.closed is True
