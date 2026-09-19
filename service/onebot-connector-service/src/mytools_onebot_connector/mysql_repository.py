"""独立连接器 schema 的 MySQL 仓储。"""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID, uuid4

from pymysql.err import IntegrityError

from .models import (
    Account,
    InboundEvent,
    InboundEventClaim,
    InboundEventStatus,
    TextReply,
    TextReplyClaim,
    TextReplyStatus,
)


class MySqlAccountRepository:
    """持久化账户且不解析或返回凭据。"""

    def __init__(self, connection_factory) -> None:
        self._connection_factory = connection_factory

    def find_by_external_key(self, external_key: str) -> Account | None:
        """通过稳定外部键返回账户。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT * FROM onebot_account WHERE external_key=%s", (external_key,))
                row = cursor.fetchone()
                return None if row is None else map_account(row)
        finally:
            connection.close()

    def save(self, account: Account) -> Account:
        """通过精确冲突检测幂等插入账户。"""
        existing = self.find_by_external_key(account.external_key)
        if existing is not None:
            comparable = ("http_base_url", "secret_ref", "host_qq_root", "container_qq_root", "enabled")
            if any(getattr(existing, field) != getattr(account, field) for field in comparable):
                raise ValueError("OneBot account idempotency conflict")
            return existing
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("""
                    INSERT INTO onebot_account
                    (id,external_key,http_base_url,secret_ref,host_qq_root,container_qq_root,
                     enabled,created_at,updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
                    """, (str(account.id), account.external_key, account.http_base_url,
                          account.secret_ref, account.host_qq_root, account.container_qq_root,
                          account.enabled, account.created_at, account.updated_at))
            connection.commit()
        finally:
            connection.close()
        return account

    def claim_text_reply(self, reply: TextReply) -> TextReplyClaim:
        """通过数据库唯一约束原子申领回复，并安全处理并发重复。"""
        connection = self._connection_factory()
        try:
            try:
                with connection.cursor() as cursor:
                    cursor.execute("""
                        INSERT INTO onebot_text_reply
                        (id,account_key,idempotency_key,message_type,target_id,message_id,
                         text_digest,status,attempt_count,attempt_token,next_attempt_at,
                         provider_message_id,last_error_code,created_at,updated_at)
                        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                        """, (str(reply.id), reply.account_key, reply.idempotency_key,
                              reply.message_type, reply.target_id, reply.message_id,
                              reply.text_digest, reply.status.value, reply.attempt_count,
                              None if reply.attempt_token is None else str(reply.attempt_token),
                              reply.next_attempt_at, reply.provider_message_id,
                              reply.last_error_code, reply.created_at, reply.updated_at))
                connection.commit()
                return TextReplyClaim(reply, True)
            except IntegrityError as exception:
                connection.rollback()
                if not exception.args or int(exception.args[0]) != 1062:
                    raise
                # 唯一键冲突可能来自另一个并发事务；回滚后再读取其已提交结果。
                existing = self._find_text_reply_by_key(
                    connection, reply.account_key, reply.idempotency_key)
                if existing is None:
                    raise RuntimeError("OneBot text reply claim could not be resolved") from exception
                if not existing.matches(reply):
                    raise ValueError("OneBot text reply idempotency conflict") from exception
                return TextReplyClaim(existing, False)
        finally:
            connection.close()

    def find_text_reply(self, reply_id: UUID) -> TextReply | None:
        """通过内部标识读取文本回复。"""
        connection = self._connection_factory()
        try:
            return self._find_text_reply_by_id(connection, reply_id)
        finally:
            connection.close()

    def start_text_reply(self, reply_id: UUID, expected_attempt_count: int,
                         attempt_token: UUID, now: datetime) -> TextReply | None:
        """通过比较交换在退避到期后取得一次唯一渠道尝试。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("""
                    UPDATE onebot_text_reply
                       SET status='IN_FLIGHT', attempt_count=attempt_count+1,
                           attempt_token=%s, last_error_code=NULL, updated_at=%s
                     WHERE id=%s AND status='PREPARED' AND attempt_count=%s
                       AND next_attempt_at<=%s
                    """, (str(attempt_token), now, str(reply_id),
                          expected_attempt_count, now))
            reply = self._find_text_reply_by_id(connection, reply_id)
            if reply is None:
                connection.rollback()
                raise RuntimeError("OneBot text reply does not exist")
            if reply.status is TextReplyStatus.IN_FLIGHT \
                    and reply.attempt_token == attempt_token \
                    and reply.attempt_count == expected_attempt_count + 1:
                connection.commit()
                return reply
            connection.rollback()
            return None
        finally:
            connection.close()

    def mark_text_reply_sent(self, reply_id: UUID, attempt_token: UUID,
                             provider_message_id: str) -> TextReply:
        """通过尝试令牌持久化明确的渠道消息标识。"""
        if not provider_message_id or len(provider_message_id) > 512:
            raise ValueError("OneBot provider message id is invalid")
        connection = self._connection_factory()
        try:
            now = datetime.now(UTC)
            with connection.cursor() as cursor:
                cursor.execute("""
                    UPDATE onebot_text_reply
                       SET status='SENT', provider_message_id=%s, attempt_token=NULL,
                           last_error_code=NULL, updated_at=%s
                     WHERE id=%s AND status='IN_FLIGHT' AND attempt_token=%s
                    """, (provider_message_id, now, str(reply_id), str(attempt_token)))
            reply = self._find_text_reply_by_id(connection, reply_id)
            if reply is None:
                connection.rollback()
                raise RuntimeError("OneBot text reply does not exist")
            if reply.status is not TextReplyStatus.SENT \
                    or reply.provider_message_id != provider_message_id:
                connection.rollback()
                raise RuntimeError("OneBot text reply sent result conflicts")
            connection.commit()
            return reply
        finally:
            connection.close()

    def mark_text_reply_uncertain(self, reply_id: UUID, attempt_token: UUID) -> TextReply:
        """仅将对应渠道尝试固化为不可判定，绝不覆盖 SENT。"""
        connection = self._connection_factory()
        try:
            now = datetime.now(UTC)
            with connection.cursor() as cursor:
                cursor.execute("""
                    UPDATE onebot_text_reply
                       SET status='UNCERTAIN', last_error_code='DELIVERY_OUTCOME_UNCERTAIN',
                           updated_at=%s
                     WHERE id=%s AND status='IN_FLIGHT' AND attempt_token=%s
                    """, (now, str(reply_id), str(attempt_token)))
            reply = self._find_text_reply_by_id(connection, reply_id)
            if reply is None:
                connection.rollback()
                raise RuntimeError("OneBot text reply does not exist")
            if reply.status is TextReplyStatus.UNCERTAIN \
                    and reply.attempt_token != attempt_token:
                connection.rollback()
                raise RuntimeError("OneBot text reply attempt is not active")
            if reply.status not in {TextReplyStatus.UNCERTAIN, TextReplyStatus.SENT}:
                connection.rollback()
                raise RuntimeError("OneBot text reply attempt is not active")
            connection.commit()
            return reply
        finally:
            connection.close()

    def reschedule_text_reply(self, reply_id: UUID, attempt_token: UUID,
                              next_attempt_at: datetime, exhausted: bool,
                              now: datetime) -> TextReply:
        """将明确未发出的尝试原子退回 PREPARED 或标记 FAILED。"""
        connection = self._connection_factory()
        try:
            target_status = "FAILED" if exhausted else "PREPARED"
            error_code = "RETRY_EXHAUSTED" if exhausted else "PROVIDER_NOT_STARTED"
            with connection.cursor() as cursor:
                cursor.execute("""
                    UPDATE onebot_text_reply
                       SET status=%s, next_attempt_at=%s, last_error_code=%s, updated_at=%s
                     WHERE id=%s AND status='IN_FLIGHT' AND attempt_token=%s
                    """, (target_status, next_attempt_at, error_code, now,
                          str(reply_id), str(attempt_token)))
            reply = self._find_text_reply_by_id(connection, reply_id)
            if reply is None:
                connection.rollback()
                raise RuntimeError("OneBot text reply does not exist")
            if reply.status.value != target_status or reply.attempt_token != attempt_token:
                connection.rollback()
                raise RuntimeError("OneBot text reply attempt is not active")
            connection.commit()
            return reply
        finally:
            connection.close()

    def claim_inbound_event(self, event: InboundEvent) -> InboundEventClaim:
        """通过唯一约束幂等持久化入站事件。"""
        connection = self._connection_factory()
        try:
            try:
                with connection.cursor() as cursor:
                    cursor.execute("""
                        INSERT INTO onebot_inbound_event
                        (id,account_key,event_key,source_message_id,payload_json,payload_digest,
                         status,attempt_count,next_attempt_at,attempt_token,lease_until,
                         last_error_code,delivered_at,dead_at,created_at,updated_at)
                        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                        """, (str(event.id), event.account_key, event.event_key,
                              event.source_message_id, event.payload_json, event.payload_digest,
                              event.status.value, event.attempt_count, event.next_attempt_at,
                              None if event.attempt_token is None else str(event.attempt_token),
                              event.lease_until, event.last_error_code, event.delivered_at,
                              event.dead_at, event.created_at, event.updated_at))
                connection.commit()
                return InboundEventClaim(event, True)
            except IntegrityError as exception:
                connection.rollback()
                if not exception.args or int(exception.args[0]) != 1062:
                    raise
                existing = self._find_inbound_event_by_key(
                    connection, event.account_key, event.event_key)
                if existing is None:
                    raise RuntimeError(
                        "OneBot inbound event claim could not be resolved") from exception
                if not existing.matches(event):
                    raise ValueError(
                        "OneBot inbound event idempotency conflict") from exception
                return InboundEventClaim(existing, False)
        finally:
            connection.close()

    def find_inbound_event(self, event_id: UUID) -> InboundEvent | None:
        """通过内部标识读取入站事件。"""
        connection = self._connection_factory()
        try:
            return self._find_inbound_event_by_id(connection, event_id)
        finally:
            connection.close()

    def claim_due_inbound_events(self, now: datetime, lease_until: datetime,
                                 limit: int, maximum_attempts: int) -> list[InboundEvent]:
        """通过行锁跳过其他 worker 已申领的事件并设置 fencing token。"""
        if limit <= 0 or maximum_attempts <= 0 or lease_until <= now:
            raise ValueError("OneBot inbound event lease is invalid")
        connection = self._connection_factory()
        try:
            try:
                claimed: list[InboundEvent] = []
                with connection.cursor() as cursor:
                    cursor.execute("""
                        UPDATE onebot_inbound_event
                           SET status='DEAD', attempt_token=NULL, lease_until=NULL,
                               last_error_code='RETRY_EXHAUSTED', dead_at=%s, updated_at=%s
                         WHERE attempt_count>=%s
                           AND ((status='PENDING' AND next_attempt_at<=%s)
                             OR (status='IN_FLIGHT' AND lease_until<=%s))
                        """, (now, now, maximum_attempts, now, now))
                    cursor.execute("""
                        SELECT id FROM onebot_inbound_event
                         WHERE attempt_count<%s
                           AND ((status='PENDING' AND next_attempt_at<=%s)
                             OR (status='IN_FLIGHT' AND lease_until<=%s))
                         ORDER BY CASE WHEN status='PENDING' THEN next_attempt_at
                                       ELSE lease_until END, created_at, id
                         LIMIT %s FOR UPDATE SKIP LOCKED
                        """, (maximum_attempts, now, now, limit))
                    event_ids = [UUID(str(row["id"])) for row in cursor.fetchall()]
                    for event_id in event_ids:
                        attempt_token = uuid4()
                        cursor.execute("""
                            UPDATE onebot_inbound_event
                               SET status='IN_FLIGHT', attempt_count=attempt_count+1,
                                   attempt_token=%s, lease_until=%s,
                                   last_error_code=NULL, updated_at=%s
                             WHERE id=%s
                               AND attempt_count<%s
                               AND ((status='PENDING' AND next_attempt_at<=%s)
                                 OR (status='IN_FLIGHT' AND lease_until<=%s))
                            """, (str(attempt_token), lease_until, now, str(event_id),
                                  maximum_attempts, now, now))
                        updated = cursor.rowcount == 1
                        if not updated:
                            continue
                        cursor.execute(
                            "SELECT * FROM onebot_inbound_event WHERE id=%s",
                            (str(event_id),))
                        row = cursor.fetchone()
                        if row is not None:
                            claimed.append(map_inbound_event(row))
                connection.commit()
                return claimed
            except Exception:
                connection.rollback()
                raise
        finally:
            connection.close()

    def mark_inbound_event_delivered(self, event_id: UUID, attempt_token: UUID,
                                     now: datetime) -> bool:
        """仅由持有当前 fencing token 的 worker 写入 DELIVERED。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("""
                    UPDATE onebot_inbound_event
                       SET status='DELIVERED', attempt_token=NULL, lease_until=NULL,
                           last_error_code=NULL, delivered_at=%s, updated_at=%s
                     WHERE id=%s AND status='IN_FLIGHT' AND attempt_token=%s
                    """, (now, now, str(event_id), str(attempt_token)))
                updated = cursor.rowcount == 1
            connection.commit()
            return updated
        finally:
            connection.close()

    def mark_inbound_event_failed(self, event_id: UUID, attempt_token: UUID,
                                  error_code: str, next_attempt_at: datetime,
                                  dead: bool, now: datetime) -> bool:
        """仅由持有当前 fencing token 的 worker 写入退避或 DEAD。"""
        connection = self._connection_factory()
        try:
            target_status = "DEAD" if dead else "PENDING"
            with connection.cursor() as cursor:
                cursor.execute("""
                    UPDATE onebot_inbound_event
                       SET status=%s, next_attempt_at=%s, attempt_token=NULL,
                           lease_until=NULL, last_error_code=%s, dead_at=%s, updated_at=%s
                     WHERE id=%s AND status='IN_FLIGHT' AND attempt_token=%s
                    """, (target_status, next_attempt_at, error_code[:128],
                          now if dead else None, now, str(event_id), str(attempt_token)))
                updated = cursor.rowcount == 1
            connection.commit()
            return updated
        finally:
            connection.close()

    @staticmethod
    def _find_text_reply_by_key(connection, account_key: str,
                                idempotency_key: str) -> TextReply | None:
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT * FROM onebot_text_reply
                 WHERE account_key=%s AND idempotency_key=%s
                """, (account_key, idempotency_key))
            row = cursor.fetchone()
        return None if row is None else map_text_reply(row)

    @staticmethod
    def _find_text_reply_by_id(connection, reply_id: UUID) -> TextReply | None:
        with connection.cursor() as cursor:
            cursor.execute("SELECT * FROM onebot_text_reply WHERE id=%s", (str(reply_id),))
            row = cursor.fetchone()
        return None if row is None else map_text_reply(row)

    @staticmethod
    def _find_inbound_event_by_key(connection, account_key: str,
                                   event_key: str) -> InboundEvent | None:
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT * FROM onebot_inbound_event
                 WHERE account_key=%s AND event_key=%s
                """, (account_key, event_key))
            row = cursor.fetchone()
        return None if row is None else map_inbound_event(row)

    @staticmethod
    def _find_inbound_event_by_id(connection, event_id: UUID) -> InboundEvent | None:
        with connection.cursor() as cursor:
            cursor.execute("SELECT * FROM onebot_inbound_event WHERE id=%s", (str(event_id),))
            row = cursor.fetchone()
        return None if row is None else map_inbound_event(row)


def map_account(row: dict) -> Account:
    """将一条字典游标记录映射为领域模型。"""
    return Account(UUID(str(row["id"])), str(row["external_key"]), str(row["http_base_url"]),
                   str(row["secret_ref"]), str(row["host_qq_root"]),
                   str(row["container_qq_root"]), bool(row["enabled"]),
                   as_utc(row["created_at"]), as_utc(row["updated_at"]))


def map_text_reply(row: dict) -> TextReply:
    """将一条字典游标记录映射为文本回复模型。"""
    return TextReply(UUID(str(row["id"])), str(row["account_key"]),
                     str(row["idempotency_key"]), str(row["message_type"]),
                     str(row["target_id"]), str(row["message_id"]),
                     str(row["text_digest"]), TextReplyStatus(str(row["status"])),
                     int(row["attempt_count"]),
                     None if row["attempt_token"] is None else UUID(str(row["attempt_token"])),
                     as_utc(row["next_attempt_at"]),
                     None if row["provider_message_id"] is None
                     else str(row["provider_message_id"]),
                     None if row["last_error_code"] is None else str(row["last_error_code"]),
                     as_utc(row["created_at"]), as_utc(row["updated_at"]))


def map_inbound_event(row: dict) -> InboundEvent:
    """将一条字典游标记录映射为入站事件模型。"""
    return InboundEvent(
        UUID(str(row["id"])), str(row["account_key"]), str(row["event_key"]),
        str(row["source_message_id"]), str(row["payload_json"]),
        str(row["payload_digest"]), InboundEventStatus(str(row["status"])),
        int(row["attempt_count"]), as_utc(row["next_attempt_at"]),
        None if row["attempt_token"] is None else UUID(str(row["attempt_token"])),
        None if row["lease_until"] is None else as_utc(row["lease_until"]),
        None if row["last_error_code"] is None else str(row["last_error_code"]),
        None if row["delivered_at"] is None else as_utc(row["delivered_at"]),
        None if row["dead_at"] is None else as_utc(row["dead_at"]),
        as_utc(row["created_at"]), as_utc(row["updated_at"]))


def as_utc(value: datetime) -> datetime:
    """将 PyMySQL 返回的时间统一为 UTC 感知值。"""
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)
