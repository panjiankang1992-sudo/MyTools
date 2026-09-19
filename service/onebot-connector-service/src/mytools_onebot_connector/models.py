"""OneBot Connector 自有模型。"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import UTC, datetime
from enum import StrEnum
from hashlib import sha256
import json
from typing import Any
from uuid import UUID, uuid4

MAXIMUM_INBOUND_PAYLOAD_BYTES = 16 * 1024 * 1024
MAXIMUM_SOURCE_MESSAGE_ID_LENGTH = 512


class ProviderRequestNotStartedError(RuntimeError):
    """表示渠道请求在任何字节写出前已经失败。"""


class ProviderTransientRejectionError(RuntimeError):
    """表示渠道明确拒绝本次请求，但故障适合有界重试。"""


class ProviderPermanentRejectionError(RuntimeError):
    """表示渠道明确永久拒绝本次请求，重复提交不会成功。"""


@dataclass(frozen=True, slots=True)
class Account:
    """不包含已解析凭据材料的服务端 OneBot 账户路由。"""

    id: UUID
    external_key: str
    http_base_url: str
    secret_ref: str
    host_qq_root: str
    container_qq_root: str
    enabled: bool
    created_at: datetime
    updated_at: datetime

    @classmethod
    def create(cls, external_key: str, http_base_url: str, secret_ref: str,
               host_qq_root: str, container_qq_root: str, enabled: bool) -> "Account":
        """在边界校验完成后创建账户聚合。"""
        now = datetime.now(UTC)
        return cls(uuid4(), external_key, http_base_url, secret_ref, host_qq_root,
                   container_qq_root, enabled, now, now)


@dataclass(frozen=True, slots=True)
class ProviderFileRequest:
    """从 Messaging 接收的标准化渠道文件请求。"""

    account_key: str
    attachment_type: str
    provider_file_id: str


@dataclass(slots=True)
class ContentSource:
    """仅在单次请求内持有的已准备本地或 HTTP 内容源。"""

    account: Account
    local_path: str | None = None
    url: str | None = None


class TextReplyStatus(StrEnum):
    """文本回复的持久化投递状态。"""

    PREPARED = "PREPARED"
    IN_FLIGHT = "IN_FLIGHT"
    SENT = "SENT"
    UNCERTAIN = "UNCERTAIN"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class TextReply:
    """以调用方幂等键标识的一次 OneBot 文本回复。"""

    id: UUID
    account_key: str
    idempotency_key: str
    message_type: str
    target_id: str
    message_id: str
    text_digest: str
    status: TextReplyStatus
    attempt_count: int
    attempt_token: UUID | None
    next_attempt_at: datetime
    provider_message_id: str | None
    last_error_code: str | None
    created_at: datetime
    updated_at: datetime

    @classmethod
    def create(cls, account_key: str, idempotency_key: str, message_type: str,
               target_id: str, message_id: str, text: str,
               now: datetime | None = None) -> "TextReply":
        """创建已校验但尚未调用渠道的一次回复占位。"""
        now = now or datetime.now(UTC)
        return cls(uuid4(), account_key, idempotency_key, message_type, target_id,
                   message_id, sha256(text.encode("utf-8")).hexdigest(),
                   TextReplyStatus.PREPARED, 0, None, now, None, None, now, now)

    def matches(self, candidate: "TextReply") -> bool:
        """判断同一幂等键是否承载完全相同的业务载荷。"""
        comparable = ("message_type", "target_id", "message_id", "text_digest")
        return self.account_key == candidate.account_key \
            and self.idempotency_key == candidate.idempotency_key \
            and all(getattr(self, field) == getattr(candidate, field) for field in comparable)

    def in_flight(self, attempt_token: UUID,
                  updated_at: datetime | None = None) -> "TextReply":
        """生成已获得唯一尝试令牌且即将调用渠道的快照。"""
        return replace(self, status=TextReplyStatus.IN_FLIGHT,
                       attempt_count=self.attempt_count + 1,
                       attempt_token=attempt_token, last_error_code=None,
                       updated_at=updated_at or datetime.now(UTC))

    def sent(self, provider_message_id: str, updated_at: datetime | None = None) -> "TextReply":
        """生成明确已发送的不可变快照。"""
        return replace(self, status=TextReplyStatus.SENT,
                       provider_message_id=provider_message_id,
                       attempt_token=None, last_error_code=None,
                       updated_at=updated_at or datetime.now(UTC))

    def uncertain(self, updated_at: datetime | None = None) -> "TextReply":
        """生成渠道结果不可判定的不可变快照。"""
        return replace(self, status=TextReplyStatus.UNCERTAIN,
                       last_error_code="DELIVERY_OUTCOME_UNCERTAIN",
                       updated_at=updated_at or datetime.now(UTC))

    def retry(self, next_attempt_at: datetime, exhausted: bool,
              updated_at: datetime | None = None) -> "TextReply":
        """生成明确未发出后的退避或重试耗尽快照。"""
        return replace(self, status=TextReplyStatus.FAILED if exhausted
                       else TextReplyStatus.PREPARED,
                       next_attempt_at=next_attempt_at,
                       last_error_code="RETRY_EXHAUSTED" if exhausted
                       else "PROVIDER_NOT_STARTED",
                       updated_at=updated_at or datetime.now(UTC))


@dataclass(frozen=True, slots=True)
class TextReplyClaim:
    """回复占位的申领结果。"""

    reply: TextReply
    claimed: bool


class InboundEventStatus(StrEnum):
    """OneBot 入站事件的持久化投递状态。"""

    PENDING = "PENDING"
    IN_FLIGHT = "IN_FLIGHT"
    DELIVERED = "DELIVERED"
    DEAD = "DEAD"


@dataclass(frozen=True, slots=True)
class InboundEvent:
    """先于 Messaging 投递持久化的一条 OneBot 入站事件。"""

    id: UUID
    account_key: str
    event_key: str
    source_message_id: str
    payload_json: str
    payload_digest: str
    status: InboundEventStatus
    attempt_count: int
    next_attempt_at: datetime
    attempt_token: UUID | None
    lease_until: datetime | None
    last_error_code: str | None
    delivered_at: datetime | None
    dead_at: datetime | None
    created_at: datetime
    updated_at: datetime

    @classmethod
    def create(cls, account_key: str, event_key: str, source_message_id: str,
               payload: dict[str, Any], now: datetime | None = None) -> "InboundEvent":
        """以规范 JSON 创建一条立即可投递的事件。"""
        if not account_key or len(account_key) > 128 or len(event_key) != 64 \
                or not source_message_id \
                or len(source_message_id) > MAXIMUM_SOURCE_MESSAGE_ID_LENGTH:
            raise ValueError("OneBot inbound event identity is invalid")
        now = now or datetime.now(UTC)
        payload_json = json.dumps(
            payload, allow_nan=False, ensure_ascii=False,
            separators=(",", ":"), sort_keys=True)
        if len(payload_json.encode("utf-8")) > MAXIMUM_INBOUND_PAYLOAD_BYTES:
            raise ValueError("OneBot inbound event payload is too large")
        return cls(uuid4(), account_key, event_key, source_message_id, payload_json,
                   sha256(payload_json.encode("utf-8")).hexdigest(),
                   InboundEventStatus.PENDING, 0, now, None, None, None,
                   None, None, now, now)

    def matches(self, candidate: "InboundEvent") -> bool:
        """判断相同事件键是否仍为完全相同的渠道载荷。"""
        return self.account_key == candidate.account_key \
            and self.event_key == candidate.event_key \
            and self.source_message_id == candidate.source_message_id \
            and self.payload_digest == candidate.payload_digest

    def payload(self) -> dict[str, Any]:
        """解析并返回持久化的 Messaging 请求。"""
        payload = json.loads(self.payload_json)
        if not isinstance(payload, dict):
            raise ValueError("OneBot inbound payload is invalid")
        return payload

    def in_flight(self, attempt_token: UUID, lease_until: datetime,
                  updated_at: datetime) -> "InboundEvent":
        """生成带租约和 fencing token 的投递快照。"""
        return replace(self, status=InboundEventStatus.IN_FLIGHT,
                       attempt_count=self.attempt_count + 1,
                       attempt_token=attempt_token, lease_until=lease_until,
                       last_error_code=None, updated_at=updated_at)

    def delivered(self, updated_at: datetime) -> "InboundEvent":
        """生成已被 Messaging 接收的终态快照。"""
        return replace(self, status=InboundEventStatus.DELIVERED,
                       attempt_token=None, lease_until=None, last_error_code=None,
                       delivered_at=updated_at, updated_at=updated_at)

    def failed(self, error_code: str, next_attempt_at: datetime, dead: bool,
               updated_at: datetime) -> "InboundEvent":
        """生成退避待重试或不可再投递的终态快照。"""
        return replace(self, status=InboundEventStatus.DEAD if dead
                       else InboundEventStatus.PENDING,
                       next_attempt_at=next_attempt_at, attempt_token=None,
                       lease_until=None, last_error_code=error_code,
                       dead_at=updated_at if dead else None, updated_at=updated_at)


@dataclass(frozen=True, slots=True)
class InboundEventClaim:
    """入站事件幂等落库的申领结果。"""

    event: InboundEvent
    claimed: bool
