"""把 NapCat OneBot WebSocket 入站事件转发到 Messaging。"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from hashlib import sha256
import json
import logging
import time
from collections.abc import Callable, Mapping
from typing import Any

import aiohttp

from .connector import resolve_secret
from .inbound_wal import InboundEventWal, InboundWalEntry
from .models import (
    MAXIMUM_INBOUND_PAYLOAD_BYTES,
    MAXIMUM_SOURCE_MESSAGE_ID_LENGTH,
    InboundEvent,
)
from .repository import AccountRepository

logger = logging.getLogger(__name__)
MAXIMUM_EVENT_BYTES = MAXIMUM_INBOUND_PAYLOAD_BYTES
MESSAGING_FORWARD_TIMEOUT = aiohttp.ClientTimeout(total=10, connect=2, sock_read=8)
RETRYABLE_HTTP_STATUSES = frozenset({408, 425, 429})
DELIVERY_BATCH_SIZE = 8
DELIVERY_CONCURRENCY = 4
DELIVERY_MAX_ATTEMPTS = 9
DELIVERY_POLL_SECONDS = 1.0
DELIVERY_LEASE_SECONDS = 30
PERSIST_MAX_ATTEMPTS = 9
WAL_IMPORT_BATCH_SIZE = 32
HEALTH_STARTUP_GRACE_SECONDS = 30.0
HEALTH_DISCONNECT_GRACE_SECONDS = 60.0
HEALTH_WORKER_STALE_SECONDS = 30.0

_HEALTH_COUNTER_KEYS = (
    "walDeadEvents",
    "databasePendingInboundEvents",
    "databaseDeadInboundEvents",
    "pendingTextReplies",
    "failedTextReplies",
    "uncertainTextReplies",
)


class WalPersistenceExhaustedError(RuntimeError):
    """表示当前渠道帧无法写入本机稳定存储。"""


class InboundMessageRejectedError(ValueError):
    """表示已识别真实消息因确定性格式或大小约束被拒绝。"""

    def __init__(self, error_code: str) -> None:
        super().__init__(error_code)
        self.error_code = error_code


class MessagingIngressError(RuntimeError):
    """包含安全错误码和可重试语义的 Messaging 入站错误。"""

    def __init__(self, status: int) -> None:
        super().__init__(f"Messaging OneBot ingress failed with HTTP {status}")
        self.error_code = f"HTTP_{status}"
        self.retryable = status in RETRYABLE_HTTP_STATUSES or 500 <= status < 600


class OneBotInboundBridge:
    """持续消费 NapCat 事件并写入统一消息入口。"""

    def __init__(self, repository: AccountRepository, account_key: str, owner_id: int,
                 websocket_url: str, messaging_url: str, messaging_token: str,
                 inbound_wal: InboundEventWal,
                 health_probe: Callable[[], Mapping[str, int]] | None = None,
                 monotonic_clock: Callable[[], float] = time.monotonic) -> None:
        if not account_key or owner_id <= 0 or not websocket_url.startswith("ws://") \
                or not messaging_url.startswith("http://") or not messaging_token:
            raise ValueError("OneBot inbound bridge configuration is invalid")
        self._repository = repository
        self._account_key = account_key
        self._owner_id = owner_id
        self._websocket_url = websocket_url
        self._messaging_url = messaging_url.rstrip("/")
        self._messaging_token = messaging_token
        self._inbound_wal = inbound_wal
        self._health_probe = health_probe or (lambda: {key: 0 for key in _HEALTH_COUNTER_KEYS})
        self._monotonic_clock = monotonic_clock
        self._delivery_wakeup = asyncio.Event()
        self._wal_wakeup = asyncio.Event()
        started_at = monotonic_clock()
        self._started_at = started_at
        self._bridge_running = False
        self._consumer_connected = False
        self._consumer_ever_connected = False
        self._consumer_disconnected_at: float | None = started_at
        self._consumer_last_frame_at: float | None = None
        self._wal_worker_running = False
        self._wal_worker_last_success_at: float | None = None
        self._delivery_worker_running = False
        self._delivery_worker_last_success_at: float | None = None
        self._fatal_error_code: str | None = None
        self._session_rejected_events = 0
        self._websocket_oversized_disconnects = 0

    async def run(self) -> None:
        """并行运行 WebSocket 收取和持久化事件投递。"""
        self._bridge_running = True
        timeout = aiohttp.ClientTimeout(total=None, connect=30, sock_read=None)
        try:
            async with aiohttp.ClientSession(timeout=timeout) as session:
                tasks = [
                    asyncio.create_task(
                        self._consume_loop(session), name="onebot-inbound-consumer"),
                    asyncio.create_task(
                        self._delivery_loop(session), name="onebot-inbound-delivery"),
                    asyncio.create_task(
                        self._wal_import_loop(), name="onebot-inbound-wal-import"),
                ]
                try:
                    completed, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                    for task in completed:
                        exception = task.exception()
                        if exception is not None:
                            raise exception
                    raise RuntimeError("OneBot inbound background worker stopped unexpectedly")
                finally:
                    for task in tasks:
                        task.cancel()
                    await asyncio.gather(*tasks, return_exceptions=True)
        except Exception as exception:
            # 只记录异常类别，健康接口不得泄露渠道载荷、凭据或下游响应正文。
            self._fatal_error_code = type(exception).__name__[:128]
            raise
        finally:
            self._bridge_running = False
            self._mark_consumer_disconnected()

    def health_snapshot(self) -> dict[str, Any]:
        """返回只含安全状态、年龄和计数的入站 readiness 快照。"""
        now = self._monotonic_clock()
        startup_age = max(0.0, now - self._started_at)
        startup_grace = startup_age <= HEALTH_STARTUP_GRACE_SECONDS
        disconnected_age = self._age(now, self._consumer_disconnected_at)
        if self._consumer_connected:
            consumer_ready = True
        elif self._consumer_ever_connected:
            consumer_ready = disconnected_age is not None \
                and disconnected_age <= HEALTH_DISCONNECT_GRACE_SECONDS
        else:
            consumer_ready = startup_grace
        wal_worker_age = self._age(now, self._wal_worker_last_success_at)
        delivery_worker_age = self._age(now, self._delivery_worker_last_success_at)
        wal_worker_ready = self._worker_ready(
            self._wal_worker_running, wal_worker_age, startup_grace)
        delivery_worker_ready = self._worker_ready(
            self._delivery_worker_running, delivery_worker_age, startup_grace)
        probe_ready = True
        probe_error_code: str | None = None
        counters = {key: -1 for key in _HEALTH_COUNTER_KEYS}
        wal_pending_events = -1
        rejected_inbound_events = -1
        try:
            wal_pending_events = self._safe_count(self._inbound_wal.pending_count())
            rejected_inbound_events = self._safe_count(self._inbound_wal.rejected_count())
            raw_counters = self._health_probe()
            counters = {key: self._safe_count(raw_counters[key]) for key in _HEALTH_COUNTER_KEYS}
        except Exception as exception:  # noqa: BLE001
            probe_ready = False
            probe_error_code = type(exception).__name__[:128]
        unhealthy_terminal_count = sum(
            max(0, counters[key]) for key in (
                "walDeadEvents", "databaseDeadInboundEvents",
                "failedTextReplies", "uncertainTextReplies"))
        healthy = self._fatal_error_code is None \
            and (self._bridge_running or startup_grace) \
            and consumer_ready and wal_worker_ready and delivery_worker_ready \
            and probe_ready and unhealthy_terminal_count == 0 \
            and rejected_inbound_events == 0 and self._session_rejected_events == 0
        return {
            "status": "UP" if healthy else "DOWN",
            "inboundEnabled": True,
            "startupGrace": startup_grace,
            "bridgeRunning": self._bridge_running,
            "consumerConnected": self._consumer_connected,
            "consumerReady": consumer_ready,
            "consumerDisconnectedAgeSeconds": disconnected_age,
            # 空闲会话没有消息帧是正常状态；该年龄仅用于诊断，不参与 readiness。
            "consumerLastFrameAgeSeconds": self._age(now, self._consumer_last_frame_at),
            "walWorkerReady": wal_worker_ready,
            "walWorkerLastSuccessAgeSeconds": wal_worker_age,
            "deliveryWorkerReady": delivery_worker_ready,
            "deliveryWorkerLastSuccessAgeSeconds": delivery_worker_age,
            "walPendingEvents": wal_pending_events,
            "rejectedInboundEvents": rejected_inbound_events,
            "sessionRejectedInboundEvents": self._session_rejected_events,
            "websocketOversizedDisconnects": self._websocket_oversized_disconnects,
            **counters,
            "healthProbeReady": probe_ready,
            "healthProbeErrorCode": probe_error_code,
            "fatalErrorCode": self._fatal_error_code,
        }

    @staticmethod
    def _safe_count(value: object) -> int:
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError("OneBot health counter is invalid")
        return value

    @staticmethod
    def _age(now: float, timestamp: float | None) -> int | None:
        return None if timestamp is None else max(0, int(now - timestamp))

    @staticmethod
    def _worker_ready(running: bool, last_success_age: int | None,
                      startup_grace: bool) -> bool:
        return running and (startup_grace or (last_success_age is not None
                                               and last_success_age <= HEALTH_WORKER_STALE_SECONDS))

    def _mark_consumer_connected(self) -> None:
        self._consumer_connected = True
        self._consumer_ever_connected = True
        self._consumer_disconnected_at = None

    def _mark_consumer_disconnected(self) -> None:
        if self._consumer_connected or self._consumer_disconnected_at is None:
            self._consumer_disconnected_at = self._monotonic_clock()
        self._consumer_connected = False

    async def _consume_loop(self, session: aiohttp.ClientSession) -> None:
        """持续重连渠道，但 WAL 致命错误必须传播到主进程。"""
        backoff = 1.0
        while True:
            try:
                await self._consume(session)
                backoff = 1.0
            except asyncio.CancelledError:
                raise
            except WalPersistenceExhaustedError:
                logger.critical("OneBot inbound bridge stopped because WAL is unavailable")
                raise
            except Exception as exception:  # noqa: BLE001
                logger.warning("OneBot inbound bridge disconnected: %s",
                               type(exception).__name__)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 60.0)

    async def _consume(self, session: aiohttp.ClientSession) -> None:
        account = self._repository.find_by_external_key(self._account_key)
        if account is None or not account.enabled:
            raise RuntimeError("OneBot inbound account is unavailable")
        token = resolve_secret(account.secret_ref)
        headers = {"Authorization": f"Bearer {token}"}
        async with session.ws_connect(self._websocket_url, headers=headers,
                                      heartbeat=30, max_msg_size=MAXIMUM_EVENT_BYTES) as websocket:
            self._mark_consumer_connected()
            logger.info("OneBot inbound bridge connected")
            try:
                async for message in websocket:
                    self._consumer_last_frame_at = self._monotonic_clock()
                    if message.type == aiohttp.WSMsgType.TEXT:
                        raw = message.data.encode("utf-8") if isinstance(message.data, str) else b""
                        try:
                            event = json.loads(message.data)
                        except (TypeError, json.JSONDecodeError):
                            if self._looks_like_message_frame(raw):
                                await self._record_rejection("INVALID_JSON", raw)
                            continue
                        try:
                            payload = self._validated_event_payload(event)
                        except InboundMessageRejectedError as exception:
                            await self._record_rejection(exception.error_code, raw)
                        else:
                            if payload is None:
                                continue
                            # WebSocket 消费路径只负责先落库，不等待下游下载或自动化处理。
                            await self._persist(payload)
                    elif message.type == aiohttp.WSMsgType.ERROR:
                        exception = websocket.exception()
                        codes = (
                            getattr(message.data, "code", None),
                            getattr(websocket, "close_code", None),
                            getattr(exception, "code", None),
                        )
                        if any(code == 1009 for code in codes):
                            self._websocket_oversized_disconnects += 1
                            await self._record_rejection(
                                "WEBSOCKET_FRAME_TOO_LARGE",
                                type(exception).__name__.encode("ascii", "replace"), -1)
                        break
                    elif message.type in {aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.CLOSE}:
                        break
            finally:
                self._mark_consumer_disconnected()

    async def _persist(self, payload: dict[str, Any]) -> InboundEvent:
        event = payload["event"]
        inbound = InboundEvent.create(
            self._account_key, self._source_event_key(event), str(event["message_id"]), payload)
        for attempt in range(1, PERSIST_MAX_ATTEMPTS + 1):
            try:
                stored = await asyncio.to_thread(self._inbound_wal.store, inbound)
                self._wal_wakeup.set()
                return stored
            except asyncio.CancelledError:
                raise
            except ValueError:
                # 幂等冲突属于确定性数据错误，重复执行不会恢复。
                raise
            except Exception as exception:  # noqa: BLE001
                if attempt >= PERSIST_MAX_ATTEMPTS:
                    logger.error(
                        "OneBot inbound persistence exhausted eventId=%s attempt=%s errorCode=%s",
                        inbound.id, attempt, type(exception).__name__)
                    raise WalPersistenceExhaustedError(
                        "OneBot inbound event persistence exhausted") from exception
                delay = 2 ** (attempt - 1)
                logger.warning(
                    "OneBot inbound persistence failed eventId=%s attempt=%s errorCode=%s",
                    inbound.id, attempt, type(exception).__name__)
                # 保持当前 WebSocket 帧并施加背压，不能通过重连假定渠道会重放。
                await asyncio.sleep(delay)
        raise WalPersistenceExhaustedError("OneBot inbound event persistence did not complete")

    async def _wal_import_loop(self) -> None:
        """把本机 WAL 有界导入数据库，并在进程重启后自动恢复。"""
        scan_failures = 0
        self._wal_worker_running = True
        try:
            while True:
                self._wal_wakeup.clear()
                try:
                    entries = await asyncio.to_thread(
                        self._inbound_wal.load_batch, datetime.now(UTC), WAL_IMPORT_BATCH_SIZE)
                except asyncio.CancelledError:
                    raise
                except Exception as exception:  # noqa: BLE001
                    scan_failures += 1
                    logger.error("OneBot inbound WAL scan failed attempt=%s errorCode=%s",
                                 scan_failures, type(exception).__name__)
                    if scan_failures >= PERSIST_MAX_ATTEMPTS:
                        raise WalPersistenceExhaustedError(
                            "OneBot inbound WAL scan exhausted") from exception
                    await asyncio.sleep(2 ** (scan_failures - 1))
                    continue
                scan_failures = 0
                self._wal_worker_last_success_at = self._monotonic_clock()
                if entries:
                    for entry in entries:
                        await self._import_wal_entry(entry)
                    continue
                try:
                    await asyncio.wait_for(
                        self._wal_wakeup.wait(), timeout=DELIVERY_POLL_SECONDS)
                except TimeoutError:
                    pass
        finally:
            self._wal_worker_running = False

    async def _import_wal_entry(self, entry: InboundWalEntry) -> None:
        """执行一次可持久恢复且不会无限重试的 WAL 数据库导入。"""
        try:
            await asyncio.to_thread(self._repository.claim_inbound_event, entry.event)
        except asyncio.CancelledError:
            raise
        except ValueError as exception:
            await asyncio.to_thread(
                self._inbound_wal.mark_dead, entry.event, "IDEMPOTENCY_CONFLICT")
            logger.error(
                "OneBot inbound WAL import dead eventId=%s attempt=%s errorCode=%s errorType=%s",
                entry.event.id, entry.attempt_count + 1,
                "IDEMPOTENCY_CONFLICT", type(exception).__name__)
        except Exception as exception:  # noqa: BLE001
            attempt_number = entry.attempt_count + 1
            error_code = type(exception).__name__[:128]
            if attempt_number >= PERSIST_MAX_ATTEMPTS:
                await asyncio.to_thread(
                    self._inbound_wal.mark_dead, entry.event, "DB_IMPORT_EXHAUSTED")
                logger.error(
                    "OneBot inbound WAL import dead eventId=%s attempt=%s errorCode=%s",
                    entry.event.id, attempt_number, "DB_IMPORT_EXHAUSTED")
                return
            retry_at = datetime.now(UTC) + timedelta(seconds=2 ** (attempt_number - 1))
            await asyncio.to_thread(
                self._inbound_wal.reschedule, entry, retry_at, error_code)
            logger.warning(
                "OneBot inbound WAL import failed eventId=%s attempt=%s errorCode=%s dead=false",
                entry.event.id, attempt_number, error_code)
        else:
            # 数据库事务已提交后才删除 WAL，崩溃重放由数据库唯一键安全吸收。
            await asyncio.to_thread(self._inbound_wal.acknowledge, entry.event)
            self._delivery_wakeup.set()

    async def _delivery_loop(self, session: aiohttp.ClientSession) -> None:
        """公平扫描到期事件，并发投递且不阻塞 WebSocket 收取。"""
        scan_failures = 0
        self._delivery_worker_running = True
        try:
            while True:
                self._delivery_wakeup.clear()
                try:
                    now = datetime.now(UTC)
                    due = await asyncio.to_thread(
                        self._repository.claim_due_inbound_events,
                        now, now + timedelta(seconds=DELIVERY_LEASE_SECONDS),
                        DELIVERY_BATCH_SIZE, DELIVERY_MAX_ATTEMPTS)
                except asyncio.CancelledError:
                    raise
                except Exception as exception:  # noqa: BLE001
                    scan_failures += 1
                    log = logger.error if scan_failures >= PERSIST_MAX_ATTEMPTS else logger.warning
                    log("OneBot inbound spool scan failed attempt=%s errorCode=%s",
                        scan_failures, type(exception).__name__)
                    if scan_failures >= PERSIST_MAX_ATTEMPTS:
                        raise RuntimeError(
                            "OneBot inbound spool scan exhausted") from exception
                    await asyncio.sleep(2 ** (scan_failures - 1))
                    continue
                # 任意一次成功查询都证明数据库扫描已经恢复，应重新给予完整故障预算。
                scan_failures = 0
                self._delivery_worker_last_success_at = self._monotonic_clock()
                if due:
                    await self._deliver_batch(session, due)
                    continue
                try:
                    await asyncio.wait_for(
                        self._delivery_wakeup.wait(), timeout=DELIVERY_POLL_SECONDS)
                except TimeoutError:
                    pass
        finally:
            self._delivery_worker_running = False

    async def _deliver_batch(self, session: aiohttp.ClientSession,
                             events: list[InboundEvent]) -> None:
        semaphore = asyncio.Semaphore(DELIVERY_CONCURRENCY)

        async def deliver(event: InboundEvent) -> None:
            async with semaphore:
                await self._deliver_event(session, event)

        results = await asyncio.gather(*(deliver(event) for event in events),
                                       return_exceptions=True)
        for result in results:
            if isinstance(result, BaseException) and not isinstance(result, asyncio.CancelledError):
                # 单条数据库状态更新失败不能停止其他事件或结束 worker。
                logger.warning("OneBot inbound delivery state update failed: %s",
                               type(result).__name__)

    async def _deliver_event(self, session: aiohttp.ClientSession,
                             event: InboundEvent) -> None:
        if event.attempt_token is None:
            raise RuntimeError("OneBot inbound event attempt is not active")
        try:
            payload = event.payload()
        except (TypeError, ValueError, json.JSONDecodeError):
            await self._record_failure(event, "INVALID_PAYLOAD", False)
            return
        try:
            await self._forward(session, payload)
        except asyncio.CancelledError:
            raise
        except MessagingIngressError as exception:
            await self._record_failure(event, exception.error_code, exception.retryable)
        except (aiohttp.ClientError, TimeoutError) as exception:
            error_code = "TIMEOUT" if isinstance(exception, TimeoutError) else "CLIENT_ERROR"
            await self._record_failure(event, error_code, True)
        except Exception as exception:  # noqa: BLE001
            await self._record_failure(event, type(exception).__name__, True)
        else:
            updated = await asyncio.to_thread(
                self._repository.mark_inbound_event_delivered,
                event.id, event.attempt_token, datetime.now(UTC))
            if not updated:
                logger.warning(
                    "OneBot inbound success ignored by fencing eventId=%s attempt=%s",
                    event.id, event.attempt_count)

    async def _record_failure(self, event: InboundEvent, error_code: str,
                              retryable: bool) -> None:
        if event.attempt_token is None:
            raise RuntimeError("OneBot inbound event attempt is not active")
        attempt_number = event.attempt_count
        dead = not retryable or attempt_number >= DELIVERY_MAX_ATTEMPTS
        now = datetime.now(UTC)
        # 九次瞬时失败之间累计等待 255 秒，覆盖常见滚动重启窗口后再进入 DEAD。
        delay_seconds = 0 if dead else 2 ** (attempt_number - 1)
        next_attempt_at = now + timedelta(seconds=delay_seconds)
        updated = await asyncio.to_thread(
            self._repository.mark_inbound_event_failed,
            event.id, event.attempt_token, error_code[:128], next_attempt_at, dead, now)
        if updated:
            log = logger.error if dead else logger.warning
            log("OneBot inbound delivery failed eventId=%s attempt=%s errorCode=%s dead=%s",
                event.id, attempt_number, error_code[:128], dead)
        else:
            logger.warning(
                "OneBot inbound failure ignored by fencing eventId=%s attempt=%s errorCode=%s",
                event.id, attempt_number, error_code[:128])

    async def _forward(self, session: aiohttp.ClientSession, payload: dict[str, Any]) -> None:
        headers = {"Authorization": f"Bearer {self._messaging_token}"}
        url = self._messaging_url + "/internal/v1/adapters/onebot/events"
        # Messaging 是本机依赖，单独设置总超时，避免继承 WebSocket 的无限读取超时。
        async with session.post(
                url, headers=headers, json=payload,
                timeout=MESSAGING_FORWARD_TIMEOUT, allow_redirects=False) as response:
            if 200 <= response.status < 300:
                return
            await response.read()
            raise MessagingIngressError(response.status)

    @staticmethod
    def _source_event_key(event: dict[str, Any]) -> str:
        """生成与 Messaging 外部消息键语义一致的稳定摘要。"""
        message_type = str(event.get("message_type") or "private")
        conversation = event.get("group_id") if message_type == "group" else event.get("user_id")
        identity = {
            "selfId": str(event.get("self_id") or ""),
            "messageType": message_type,
            "conversation": str(conversation or ""),
            "messageId": str(event.get("message_id") or ""),
        }
        canonical = json.dumps(identity, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        return sha256(canonical.encode("utf-8")).hexdigest()

    def event_payload(self, event: Any) -> dict[str, Any] | None:
        """只把真实入站消息包装成 Messaging 适配器请求。"""
        try:
            return self._validated_event_payload(event)
        except InboundMessageRejectedError:
            return None

    def _validated_event_payload(self, event: Any) -> dict[str, Any] | None:
        """区分可安全忽略的非消息事件与必须留痕的真实消息拒绝。"""
        if not isinstance(event, dict) or event.get("post_type") != "message":
            return None
        message_id = str(event.get("message_id") or "")
        if not message_id:
            raise InboundMessageRejectedError("MISSING_MESSAGE_ID")
        if len(message_id) > MAXIMUM_SOURCE_MESSAGE_ID_LENGTH:
            raise InboundMessageRejectedError("MESSAGE_ID_TOO_LONG")
        # 发送回执产生的message_sent事件必须过滤，防止自动化回复形成消息环路。
        payload = {"ownerId": self._owner_id, "accountId": self._account_key, "event": event}
        try:
            encoded = json.dumps(
                payload, allow_nan=False, ensure_ascii=False,
                separators=(",", ":"), sort_keys=True).encode("utf-8")
        except (TypeError, ValueError):
            raise InboundMessageRejectedError("NON_CANONICAL_JSON") from None
        if len(encoded) > MAXIMUM_INBOUND_PAYLOAD_BYTES:
            raise InboundMessageRejectedError("PAYLOAD_TOO_LARGE")
        return payload

    async def _record_rejection(self, error_code: str, raw: bytes,
                                observed_length: int | None = None) -> None:
        """落盘安全墓碑；失败时立即使 bridge 致命并停止继续消费。"""
        digest = sha256(raw).hexdigest()
        length = len(raw) if observed_length is None else observed_length
        try:
            await asyncio.to_thread(
                self._inbound_wal.record_rejection, error_code, digest, length)
        except asyncio.CancelledError:
            raise
        except Exception as exception:  # noqa: BLE001
            self._session_rejected_events += 1
            self._fatal_error_code = "REJECTION_PERSISTENCE_FAILED"
            raise WalPersistenceExhaustedError(
                "OneBot inbound rejection persistence failed") from exception
        self._session_rejected_events += 1
        logger.error("OneBot inbound message rejected errorCode=%s", error_code)

    @staticmethod
    def _looks_like_message_frame(raw: bytes) -> bool:
        """保守识别损坏 JSON 中明确出现的 OneBot message 类型标记。"""
        compact = b"".join(raw.split())
        return b'"post_type":"message"' in compact
