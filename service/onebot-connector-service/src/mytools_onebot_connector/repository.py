"""仓储契约和确定性的内存实现。"""

from __future__ import annotations

from datetime import datetime
from threading import RLock
from typing import Protocol
from uuid import UUID, uuid4

from .models import (
    Account,
    InboundEvent,
    InboundEventClaim,
    InboundEventStatus,
    TextReply,
    TextReplyClaim,
    TextReplyStatus,
)


class AccountRepository(Protocol):
    """连接器所需的持久化操作。"""

    def find_by_external_key(self, external_key: str) -> Account | None:
        """通过稳定键返回一个账户。"""

    def save(self, account: Account) -> Account:
        """插入账户或返回等价的已有账户。"""

    def claim_text_reply(self, reply: TextReply) -> TextReplyClaim:
        """以账户和幂等键原子申领一次文本回复。"""

    def find_text_reply(self, reply_id: UUID) -> TextReply | None:
        """通过内部标识读取文本回复。"""

    def start_text_reply(self, reply_id: UUID, expected_attempt_count: int,
                         attempt_token: UUID, now: datetime) -> TextReply | None:
        """在退避到期后原子进入 IN_FLIGHT。"""

    def mark_text_reply_sent(self, reply_id: UUID, attempt_token: UUID,
                             provider_message_id: str) -> TextReply:
        """将已申领回复标记为渠道明确接收。"""

    def mark_text_reply_uncertain(self, reply_id: UUID, attempt_token: UUID) -> TextReply:
        """将渠道调用中的回复标记为结果不可判定。"""

    def reschedule_text_reply(self, reply_id: UUID, attempt_token: UUID,
                              next_attempt_at: datetime, exhausted: bool,
                              now: datetime) -> TextReply:
        """将明确未发出的回复持久退避或标记耗尽。"""

    def claim_inbound_event(self, event: InboundEvent) -> InboundEventClaim:
        """以账户和事件键幂等持久化一条入站事件。"""

    def find_inbound_event(self, event_id: UUID) -> InboundEvent | None:
        """通过内部标识读取入站事件。"""

    def claim_due_inbound_events(self, now: datetime, lease_until: datetime,
                                 limit: int, maximum_attempts: int) -> list[InboundEvent]:
        """按公平顺序原子申领到期或租约过期的事件。"""

    def mark_inbound_event_delivered(self, event_id: UUID, attempt_token: UUID,
                                     now: datetime) -> bool:
        """使用 fencing token 将当前尝试标记为成功。"""

    def mark_inbound_event_failed(self, event_id: UUID, attempt_token: UUID,
                                  error_code: str, next_attempt_at: datetime,
                                  dead: bool, now: datetime) -> bool:
        """使用 fencing token 将当前尝试退避或标记 DEAD。"""


class InMemoryAccountRepository:
    """供契约测试使用的内存仓储。"""

    def __init__(self) -> None:
        self._accounts: dict[str, Account] = {}
        self._text_replies: dict[tuple[str, str], TextReply] = {}
        self._text_reply_keys: dict[UUID, tuple[str, str]] = {}
        self._inbound_events: dict[tuple[str, str], InboundEvent] = {}
        self._inbound_event_keys: dict[UUID, tuple[str, str]] = {}
        self._lock = RLock()

    def find_by_external_key(self, external_key: str) -> Account | None:
        """通过稳定键返回一个账户。"""
        with self._lock:
            return self._accounts.get(external_key)

    def save(self, account: Account) -> Account:
        """幂等保存等价账户并拒绝冲突。"""
        with self._lock:
            existing = self._accounts.get(account.external_key)
            if existing is not None:
                comparable = ("http_base_url", "secret_ref", "host_qq_root",
                              "container_qq_root", "enabled")
                if any(getattr(existing, field) != getattr(account, field)
                       for field in comparable):
                    raise ValueError("OneBot account idempotency conflict")
                return existing
            self._accounts[account.external_key] = account
            return account

    def claim_text_reply(self, reply: TextReply) -> TextReplyClaim:
        """线程安全地申领回复并拒绝同键不同载荷。"""
        key = (reply.account_key, reply.idempotency_key)
        with self._lock:
            existing = self._text_replies.get(key)
            if existing is not None:
                if not existing.matches(reply):
                    raise ValueError("OneBot text reply idempotency conflict")
                return TextReplyClaim(existing, False)
            self._text_replies[key] = reply
            self._text_reply_keys[reply.id] = key
            return TextReplyClaim(reply, True)

    def find_text_reply(self, reply_id: UUID) -> TextReply | None:
        """线程安全地通过内部标识读取回复。"""
        with self._lock:
            key = self._text_reply_keys.get(reply_id)
            return None if key is None else self._text_replies[key]

    def start_text_reply(self, reply_id: UUID, expected_attempt_count: int,
                         attempt_token: UUID, now: datetime) -> TextReply | None:
        """仅允许到期 PREPARED 以比较交换方式进入 IN_FLIGHT。"""
        with self._lock:
            reply = self._required_text_reply(reply_id)
            if reply.status is not TextReplyStatus.PREPARED \
                    or reply.attempt_count != expected_attempt_count \
                    or reply.next_attempt_at > now:
                return None
            updated = reply.in_flight(attempt_token, now)
            self._text_replies[self._text_reply_keys[reply_id]] = updated
            return updated

    def mark_text_reply_sent(self, reply_id: UUID, attempt_token: UUID,
                             provider_message_id: str) -> TextReply:
        """仅允许对应 IN_FLIGHT 转换为 SENT，并支持相同结果重放。"""
        if not provider_message_id or len(provider_message_id) > 512:
            raise ValueError("OneBot provider message id is invalid")
        with self._lock:
            reply = self._required_text_reply(reply_id)
            if reply.status is TextReplyStatus.SENT:
                if reply.provider_message_id != provider_message_id:
                    raise RuntimeError("OneBot text reply sent result conflicts")
                return reply
            if reply.status is not TextReplyStatus.IN_FLIGHT \
                    or reply.attempt_token != attempt_token:
                raise RuntimeError("OneBot text reply attempt is not active")
            updated = reply.sent(provider_message_id)
            self._text_replies[self._text_reply_keys[reply_id]] = updated
            return updated

    def mark_text_reply_uncertain(self, reply_id: UUID, attempt_token: UUID) -> TextReply:
        """保留 SENT 结果，否则将对应 IN_FLIGHT 固化为 UNCERTAIN。"""
        with self._lock:
            reply = self._required_text_reply(reply_id)
            if reply.status is TextReplyStatus.SENT:
                return reply
            if reply.status is TextReplyStatus.UNCERTAIN \
                    and reply.attempt_token == attempt_token:
                return reply
            if reply.status is not TextReplyStatus.IN_FLIGHT \
                    or reply.attempt_token != attempt_token:
                raise RuntimeError("OneBot text reply attempt is not active")
            updated = reply.uncertain()
            self._text_replies[self._text_reply_keys[reply_id]] = updated
            return updated

    def reschedule_text_reply(self, reply_id: UUID, attempt_token: UUID,
                              next_attempt_at: datetime, exhausted: bool,
                              now: datetime) -> TextReply:
        """仅对明确未调用成功的尝试执行持久退避。"""
        with self._lock:
            reply = self._required_text_reply(reply_id)
            if reply.status in {TextReplyStatus.PREPARED, TextReplyStatus.FAILED} \
                    and reply.attempt_token == attempt_token:
                return reply
            if reply.status is not TextReplyStatus.IN_FLIGHT \
                    or reply.attempt_token != attempt_token:
                raise RuntimeError("OneBot text reply attempt is not active")
            updated = reply.retry(next_attempt_at, exhausted, now)
            self._text_replies[self._text_reply_keys[reply_id]] = updated
            return updated

    def claim_inbound_event(self, event: InboundEvent) -> InboundEventClaim:
        """线程安全地幂等落库并拒绝同键不同载荷。"""
        key = (event.account_key, event.event_key)
        with self._lock:
            existing = self._inbound_events.get(key)
            if existing is not None:
                if not existing.matches(event):
                    raise ValueError("OneBot inbound event idempotency conflict")
                return InboundEventClaim(existing, False)
            self._inbound_events[key] = event
            self._inbound_event_keys[event.id] = key
            return InboundEventClaim(event, True)

    def find_inbound_event(self, event_id: UUID) -> InboundEvent | None:
        """线程安全地通过内部标识读取入站事件。"""
        with self._lock:
            key = self._inbound_event_keys.get(event_id)
            return None if key is None else self._inbound_events[key]

    def claim_due_inbound_events(self, now: datetime, lease_until: datetime,
                                 limit: int, maximum_attempts: int) -> list[InboundEvent]:
        """使用租约和唯一尝试令牌公平申领事件。"""
        if limit <= 0 or maximum_attempts <= 0 or lease_until <= now:
            raise ValueError("OneBot inbound event lease is invalid")
        with self._lock:
            # 已消耗完次数的崩溃尝试在租约到期后直接终结，避免无限重领。
            for event in list(self._inbound_events.values()):
                if self._is_inbound_event_due(event, now) \
                        and event.attempt_count >= maximum_attempts:
                    updated = event.failed("RETRY_EXHAUSTED", now, True, now)
                    self._inbound_events[self._inbound_event_keys[event.id]] = updated
            due = [event for event in self._inbound_events.values()
                   if self._is_inbound_event_due(event, now)
                   and event.attempt_count < maximum_attempts]
            due.sort(key=lambda event: (
                event.lease_until if event.status is InboundEventStatus.IN_FLIGHT
                else event.next_attempt_at,
                event.created_at,
                str(event.id)))
            claimed: list[InboundEvent] = []
            for event in due[:limit]:
                updated = event.in_flight(uuid4(), lease_until, now)
                self._inbound_events[self._inbound_event_keys[event.id]] = updated
                claimed.append(updated)
            return claimed

    def mark_inbound_event_delivered(self, event_id: UUID, attempt_token: UUID,
                                     now: datetime) -> bool:
        """仅允许当前租约持有者写入 DELIVERED。"""
        with self._lock:
            event = self._required_inbound_event(event_id)
            if event.status is not InboundEventStatus.IN_FLIGHT \
                    or event.attempt_token != attempt_token:
                return False
            self._inbound_events[self._inbound_event_keys[event_id]] = event.delivered(now)
            return True

    def mark_inbound_event_failed(self, event_id: UUID, attempt_token: UUID,
                                  error_code: str, next_attempt_at: datetime,
                                  dead: bool, now: datetime) -> bool:
        """仅允许当前租约持有者写入重试或 DEAD。"""
        with self._lock:
            event = self._required_inbound_event(event_id)
            if event.status is not InboundEventStatus.IN_FLIGHT \
                    or event.attempt_token != attempt_token:
                return False
            updated = event.failed(error_code, next_attempt_at, dead, now)
            self._inbound_events[self._inbound_event_keys[event_id]] = updated
            return True

    @staticmethod
    def _is_inbound_event_due(event: InboundEvent, now: datetime) -> bool:
        if event.status is InboundEventStatus.PENDING:
            return event.next_attempt_at <= now
        return event.status is InboundEventStatus.IN_FLIGHT \
            and event.lease_until is not None and event.lease_until <= now

    def _required_text_reply(self, reply_id: UUID) -> TextReply:
        key = self._text_reply_keys.get(reply_id)
        if key is None:
            raise RuntimeError("OneBot text reply does not exist")
        return self._text_replies[key]

    def _required_inbound_event(self, event_id: UUID) -> InboundEvent:
        key = self._inbound_event_keys.get(event_id)
        if key is None:
            raise RuntimeError("OneBot inbound event does not exist")
        return self._inbound_events[key]
