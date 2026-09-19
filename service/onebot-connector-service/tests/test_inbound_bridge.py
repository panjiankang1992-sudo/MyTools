"""OneBot 入站桥接测试。"""

import asyncio
from collections.abc import Iterable
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest import IsolatedAsyncioTestCase
from unittest.mock import AsyncMock, call, patch

import pytest
import aiohttp

from mytools_onebot_connector.inbound_bridge import (
    DELIVERY_MAX_ATTEMPTS,
    HEALTH_DISCONNECT_GRACE_SECONDS,
    HEALTH_WORKER_STALE_SECONDS,
    MAXIMUM_EVENT_BYTES,
    MESSAGING_FORWARD_TIMEOUT,
    OneBotInboundBridge,
    PERSIST_MAX_ATTEMPTS,
    WalPersistenceExhaustedError,
)
from mytools_onebot_connector.inbound_wal import InboundWalCapacityError, InboundWalEntry
from mytools_onebot_connector.models import InboundEventStatus
from mytools_onebot_connector.repository import InMemoryAccountRepository


class InMemoryInboundWal:
    """无需文件系统的 WAL 契约实现。"""

    def __init__(self) -> None:
        self.entries: dict[tuple[str, str], InboundWalEntry] = {}
        self.dead: list[tuple[InboundWalEntry, str]] = []
        self.rejections: list[tuple[str, str, int]] = []

    def store(self, event):
        """幂等保存事件。"""
        key = (event.account_key, event.event_key)
        existing = self.entries.get(key)
        if existing is not None:
            if not existing.event.matches(event):
                raise ValueError("OneBot inbound WAL idempotency conflict")
            return existing.event
        self.entries[key] = InboundWalEntry(event, 0, event.created_at, None)
        return event

    def load_batch(self, now, limit):
        """按退避时间返回到期记录。"""
        due = [entry for entry in self.entries.values() if entry.next_attempt_at <= now]
        due.sort(key=lambda entry: (entry.next_attempt_at, entry.event.created_at))
        return due[:limit]

    def reschedule(self, entry, next_attempt_at, error_code):
        """持久化数据库导入重试。"""
        key = (entry.event.account_key, entry.event.event_key)
        current = self.entries[key]
        if current.attempt_count != entry.attempt_count:
            raise RuntimeError("OneBot inbound WAL attempt is stale")
        updated = InboundWalEntry(
            entry.event, entry.attempt_count + 1, next_attempt_at, error_code)
        self.entries[key] = updated
        return updated

    def acknowledge(self, event):
        """移除已导入记录。"""
        self.entries.pop((event.account_key, event.event_key), None)

    def mark_dead(self, event, error_code):
        """隔离不再自动重试的记录。"""
        entry = self.entries.pop((event.account_key, event.event_key))
        self.dead.append((entry, error_code))

    def pending_count(self):
        """返回待导入数量。"""
        return len(self.entries)

    def record_rejection(self, error_code, payload_digest, payload_length):
        """保存不含原始消息的拒绝元数据。"""
        self.rejections.append((error_code, payload_digest, payload_length))

    def rejected_count(self):
        """返回拒绝元数据数量。"""
        return len(self.rejections)


def bridge(repository: InMemoryAccountRepository | None = None,
           wal: InMemoryInboundWal | None = None, health_probe=None,
           monotonic_clock=None) -> OneBotInboundBridge:
    """创建不访问网络的桥接实例。"""
    arguments = [repository or InMemoryAccountRepository(), "qq-napcat", 7,
                 "ws://127.0.0.1:3001", "http://127.0.0.1:23250", "secret",
                 wal or InMemoryInboundWal()]
    options = {}
    if health_probe is not None:
        options["health_probe"] = health_probe
    if monotonic_clock is not None:
        options["monotonic_clock"] = monotonic_clock
    return OneBotInboundBridge(*arguments, **options)


class FakeMonotonicClock:
    """提供可控的单调时间。"""

    def __init__(self) -> None:
        self.now = 100.0

    def __call__(self) -> float:
        """返回当前单调时间。"""
        return self.now

    def advance(self, seconds: float) -> None:
        """推进测试时间。"""
        self.now += seconds


def healthy_bridge(clock: FakeMonotonicClock, health_probe=None) -> OneBotInboundBridge:
    """创建已连接且两个 worker 正常扫描的桥接。"""
    connector = bridge(health_probe=health_probe, monotonic_clock=clock)
    connector._bridge_running = True
    connector._mark_consumer_connected()
    connector._wal_worker_running = True
    connector._wal_worker_last_success_at = clock()
    connector._delivery_worker_running = True
    connector._delivery_worker_last_success_at = clock()
    return connector


def payload(connector: OneBotInboundBridge, message_id: int = 99) -> dict:
    """创建具有稳定身份字段的入站请求。"""
    event = {"post_type": "message", "message_type": "private", "message_id": message_id,
             "self_id": 1000, "user_id": 42, "message": [{"type": "text"}]}
    result = connector.event_payload(event)
    assert result is not None
    return result


def claim_due(repository: InMemoryAccountRepository, limit: int = 1):
    """申领当前或短暂退避后的测试事件。"""
    now = datetime.now(UTC) + timedelta(days=1)
    return repository.claim_due_inbound_events(
        now, now + timedelta(seconds=30), limit, DELIVERY_MAX_ATTEMPTS)


async def persist_and_import(connector: OneBotInboundBridge, request: dict):
    """先写 WAL，再执行一次成功的数据库导入。"""
    event = await connector._persist(request)
    entries = connector._inbound_wal.load_batch(datetime.now(UTC) + timedelta(days=1), 1)
    assert len(entries) == 1
    await connector._import_wal_entry(entries[0])
    return event


def test_wraps_real_inbound_message() -> None:
    """真实入站消息应保留完整事件并补充账户上下文。"""
    event = {"post_type": "message", "message_type": "private",
             "message_id": 99, "user_id": 42, "message": [{"type": "text"}]}
    assert bridge().event_payload(event) == {
        "ownerId": 7, "accountId": "qq-napcat", "event": event}


def test_ignores_outbound_and_meta_events() -> None:
    """发送事件和元事件不得进入自动化形成回复环路。"""
    connector = bridge()
    assert connector.event_payload({"post_type": "message_sent", "message_id": 99}) is None
    assert connector.event_payload({"post_type": "meta_event", "message_id": 99}) is None
    assert connector.event_payload({"post_type": "message"}) is None


def test_rejects_events_that_cannot_fit_the_spool_schema() -> None:
    """过长消息标识和超大规范载荷不得触发数据库错误重连。"""
    connector = bridge()
    assert connector.event_payload({
        "post_type": "message", "message_id": "x" * 513}) is None
    assert connector.event_payload({
        "post_type": "message", "message_id": 1,
        "message": "x" * MAXIMUM_EVENT_BYTES}) is None


def test_rejected_real_message_writes_safe_metadata_and_fails_health() -> None:
    """真实消息的确定性拒绝必须落安全墓碑并使 readiness 失败。"""
    wal = InMemoryInboundWal()
    connector = bridge(wal=wal)
    raw = b'{"post_type":"message","message_id":null,"secret":"private"}'

    asyncio.run(connector._record_rejection("MISSING_MESSAGE_ID", raw))
    snapshot = connector.health_snapshot()

    assert len(wal.rejections) == 1
    code, digest, length = wal.rejections[0]
    assert code == "MISSING_MESSAGE_ID"
    assert len(digest) == 64
    assert length == len(raw)
    assert "private" not in str(wal.rejections)
    assert snapshot["status"] == "DOWN"
    assert snapshot["rejectedInboundEvents"] == 1
    assert snapshot["sessionRejectedInboundEvents"] == 1


def test_rejection_persistence_failure_is_fatal_without_leaking_payload() -> None:
    """拒绝墓碑无法持久化时必须停止消费并只暴露安全错误码。"""
    wal = InMemoryInboundWal()

    def fail_rejection(*_arguments):
        raise OSError("private")

    wal.record_rejection = fail_rejection
    connector = bridge(wal=wal)

    with pytest.raises(WalPersistenceExhaustedError):
        asyncio.run(connector._record_rejection("INVALID_JSON", b"private-message"))

    snapshot = connector.health_snapshot()
    assert snapshot["status"] == "DOWN"
    assert snapshot["fatalErrorCode"] == "REJECTION_PERSISTENCE_FAILED"
    assert "private-message" not in str(snapshot)


class FakeWebSocket:
    """提供有限帧序列和安全关闭码的 WebSocket 测试替身。"""

    def __init__(self, messages, code=1000, exception_value=None) -> None:
        self._messages = messages
        self.close_code = code
        self._exception_value = exception_value

    def __aiter__(self):
        """返回异步帧迭代器。"""
        async def iterate():
            for message in self._messages:
                yield message
        return iterate()

    def exception(self):
        """返回仅含关闭码的异常替身。"""
        return self._exception_value


class FakeWebSocketContext:
    """包装异步连接上下文。"""

    def __init__(self, websocket) -> None:
        self._websocket = websocket

    async def __aenter__(self):
        """返回测试 WebSocket。"""
        return self._websocket

    async def __aexit__(self, *_arguments):
        """结束测试连接。"""
        return False


def test_only_oversized_websocket_error_becomes_durable_rejection() -> None:
    """普通断线继续重连，只有明确 1009 才形成拒绝墓碑并使健康失败。"""
    repository = SimpleNamespace(find_by_external_key=lambda _key: SimpleNamespace(
        enabled=True, secret_ref="env://ONEBOT_TEST_TOKEN"))

    async def consume(code):
        wal = InMemoryInboundWal()
        connector = bridge(repository=repository, wal=wal)
        error = aiohttp.WebSocketError(code, "bounded test error")
        websocket = FakeWebSocket(
            [aiohttp.WSMessage(aiohttp.WSMsgType.ERROR, error, None)], code,
            exception_value=None)
        session = SimpleNamespace(
            ws_connect=lambda *_arguments, **_options: FakeWebSocketContext(websocket))
        with patch("mytools_onebot_connector.inbound_bridge.resolve_secret",
                   return_value="secret"):
            await connector._consume(session)
        return connector, wal

    ordinary, ordinary_wal = asyncio.run(consume(1006))
    oversized, oversized_wal = asyncio.run(consume(1009))
    assert ordinary_wal.rejections == []
    assert ordinary._websocket_oversized_disconnects == 0
    assert oversized_wal.rejections[0][0] == "WEBSOCKET_FRAME_TOO_LARGE"
    assert oversized_wal.rejections[0][2] == -1
    assert oversized._websocket_oversized_disconnects == 1


def test_consumer_distinguishes_ignored_events_from_rejected_messages() -> None:
    """非消息静默忽略，明确消息的坏 JSON 和缺失 ID 必须分别留痕。"""
    repository = SimpleNamespace(find_by_external_key=lambda _key: SimpleNamespace(
        enabled=True, secret_ref="env://ONEBOT_TEST_TOKEN"))
    wal = InMemoryInboundWal()
    connector = bridge(repository=repository, wal=wal)
    frames = [
        aiohttp.WSMessage(aiohttp.WSMsgType.TEXT, '{"post_type":"meta_event"}', ""),
        aiohttp.WSMessage(
            aiohttp.WSMsgType.TEXT, '{"post_type":"message","private":"value"', ""),
        aiohttp.WSMessage(aiohttp.WSMsgType.TEXT, '{"post_type":"message"}', ""),
    ]
    websocket = FakeWebSocket(frames)
    session = SimpleNamespace(
        ws_connect=lambda *_arguments, **_options: FakeWebSocketContext(websocket))

    with patch("mytools_onebot_connector.inbound_bridge.resolve_secret",
               return_value="secret"):
        asyncio.run(connector._consume(session))

    assert [item[0] for item in wal.rejections] == ["INVALID_JSON", "MISSING_MESSAGE_ID"]
    assert all("private" not in str(item) for item in wal.rejections)


def test_health_keeps_an_idle_connected_consumer_ready() -> None:
    """长时间没有消息帧不能把正常空闲的 QQ 连接误报为故障。"""
    clock = FakeMonotonicClock()
    connector = healthy_bridge(clock)
    clock.advance(HEALTH_WORKER_STALE_SECONDS + 20)
    connector._wal_worker_last_success_at = clock()
    connector._delivery_worker_last_success_at = clock()

    snapshot = connector.health_snapshot()

    assert snapshot["status"] == "UP"
    assert snapshot["consumerConnected"] is True
    assert snapshot["consumerLastFrameAgeSeconds"] is None


def test_health_fails_after_sustained_consumer_disconnect() -> None:
    """短暂重连留出宽限，长期断连必须让 readiness 失败。"""
    clock = FakeMonotonicClock()
    connector = healthy_bridge(clock)
    connector._mark_consumer_disconnected()
    clock.advance(HEALTH_DISCONNECT_GRACE_SECONDS + 1)
    connector._wal_worker_last_success_at = clock()
    connector._delivery_worker_last_success_at = clock()

    snapshot = connector.health_snapshot()

    assert snapshot["status"] == "DOWN"
    assert snapshot["consumerReady"] is False
    assert snapshot["consumerDisconnectedAgeSeconds"] \
        == HEALTH_DISCONNECT_GRACE_SECONDS + 1


def test_health_fails_for_terminal_backlog_and_worker_staleness() -> None:
    """隔离记录、不可判定回复和静默 worker 均不得继续报告就绪。"""
    clock = FakeMonotonicClock()
    counters = {
        "walDeadEvents": 1,
        "databasePendingInboundEvents": 2,
        "databaseDeadInboundEvents": 0,
        "pendingTextReplies": 3,
        "failedTextReplies": 0,
        "uncertainTextReplies": 1,
    }
    connector = healthy_bridge(clock, lambda: counters)

    snapshot = connector.health_snapshot()

    assert snapshot["status"] == "DOWN"
    assert snapshot["walDeadEvents"] == 1
    assert snapshot["uncertainTextReplies"] == 1

    counters["walDeadEvents"] = 0
    counters["uncertainTextReplies"] = 0
    clock.advance(HEALTH_WORKER_STALE_SECONDS + 1)
    snapshot = connector.health_snapshot()
    assert snapshot["status"] == "DOWN"
    assert snapshot["walWorkerReady"] is False
    assert snapshot["deliveryWorkerReady"] is False


def test_health_reports_only_probe_error_class() -> None:
    """健康探针异常不得泄露异常正文。"""
    def failed_probe():
        raise RuntimeError("secret database response")

    clock = FakeMonotonicClock()
    connector = healthy_bridge(clock, failed_probe)

    snapshot = connector.health_snapshot()

    assert snapshot["status"] == "DOWN"
    assert snapshot["healthProbeReady"] is False
    assert snapshot["healthProbeErrorCode"] == "RuntimeError"
    assert "secret" not in str(snapshot)


class FakeResponse:
    """模拟 aiohttp 响应。"""

    def __init__(self, status: int) -> None:
        self.status = status
        self.read_calls = 0

    async def read(self) -> bytes:
        """记录错误响应体已被消费。"""
        self.read_calls += 1
        return b""


class FakeRequestContext:
    """模拟 aiohttp 请求上下文。"""

    def __init__(self, result: FakeResponse | BaseException) -> None:
        self._result = result

    async def __aenter__(self) -> FakeResponse:
        """返回响应或模拟连接阶段异常。"""
        if isinstance(self._result, BaseException):
            raise self._result
        return self._result

    async def __aexit__(self, _exception_type, _exception, _traceback) -> None:
        """结束请求上下文。"""


class FakeSession:
    """按顺序返回预设请求结果并记录调用参数。"""

    def __init__(self, results: Iterable[FakeResponse | BaseException]) -> None:
        self._results = iter(results)
        self.calls: list[dict] = []

    def post(self, url: str, **kwargs) -> FakeRequestContext:
        """创建一次模拟请求。"""
        self.calls.append({"url": url, **kwargs})
        return FakeRequestContext(next(self._results))


class BlockingRequestContext:
    """等待测试信号后才返回成功响应。"""

    def __init__(self, started: asyncio.Event, release: asyncio.Event) -> None:
        self._started = started
        self._release = release

    async def __aenter__(self) -> FakeResponse:
        """通知请求已开始并等待释放。"""
        self._started.set()
        await self._release.wait()
        return FakeResponse(200)

    async def __aexit__(self, _exception_type, _exception, _traceback) -> None:
        """结束请求上下文。"""


class BlockingSession:
    """提供一个可控的慢 Messaging 请求。"""

    def __init__(self, started: asyncio.Event, release: asyncio.Event) -> None:
        self._started = started
        self._release = release

    def post(self, _url: str, **_kwargs) -> BlockingRequestContext:
        """创建慢请求上下文。"""
        return BlockingRequestContext(self._started, self._release)


class FlakyPersistenceRepository(InMemoryAccountRepository):
    """在指定次数内模拟数据库瞬时不可用。"""

    def __init__(self, failure_count: int) -> None:
        super().__init__()
        self._failure_count = failure_count
        self.claim_calls = 0

    def claim_inbound_event(self, event):
        """先抛出瞬时连接错误，再使用真实内存实现。"""
        self.claim_calls += 1
        if self.claim_calls <= self._failure_count:
            raise ConnectionError("database unavailable")
        return super().claim_inbound_event(event)


class FlakyInboundWal(InMemoryInboundWal):
    """在指定次数内模拟本机 WAL 写入失败。"""

    def __init__(self, failure_count: int) -> None:
        super().__init__()
        self._failure_count = failure_count
        self.store_calls = 0

    def store(self, event):
        """先抛出磁盘错误，再使用真实内存实现。"""
        self.store_calls += 1
        if self.store_calls <= self._failure_count:
            raise OSError("disk unavailable")
        return super().store(event)


class CapacityInboundWal(InMemoryInboundWal):
    """模拟 pending 容量持续已满。"""

    def __init__(self) -> None:
        super().__init__()
        self.store_calls = 0

    def store(self, event):
        """拒绝当前帧且不修改既有 WAL。"""
        self.store_calls += 1
        raise InboundWalCapacityError("pending capacity reached")


class FailingWalScan(InMemoryInboundWal):
    """模拟 WAL 目录持续无法扫描。"""

    def __init__(self, failure_count: int) -> None:
        super().__init__()
        self._failure_count = failure_count
        self.scan_calls = 0

    def load_batch(self, now, limit):
        """在指定扫描次数内抛出磁盘错误。"""
        self.scan_calls += 1
        if self.scan_calls <= self._failure_count:
            raise OSError("WAL directory unavailable")
        return super().load_batch(now, limit)


class ScriptedDeliveryScanRepository(InMemoryAccountRepository):
    """按脚本模拟数据库队列扫描失败和恢复。"""

    def __init__(self, outcomes: Iterable[bool]) -> None:
        super().__init__()
        self._outcomes = iter(outcomes)
        self.scan_calls = 0

    def claim_due_inbound_events(self, now, lease_until, limit, maximum_attempts):
        """False 表示失败，True 表示执行正常扫描。"""
        self.scan_calls += 1
        try:
            succeeds = next(self._outcomes)
        except StopIteration as exception:
            raise AssertionError("delivery scan script exhausted") from exception
        if not succeeds:
            raise ConnectionError("database unavailable")
        return super().claim_due_inbound_events(
            now, lease_until, limit, maximum_attempts)


class TestOneBotInboundForwarding(IsolatedAsyncioTestCase):
    """验证 Messaging 持久化转发和有限重试策略。"""

    async def test_persists_duplicate_event_once_before_delivery(self) -> None:
        """同一个渠道事件应先落库，重复接收不得生成第二条记录。"""
        repository = InMemoryAccountRepository()
        wal = InMemoryInboundWal()
        connector = bridge(repository, wal)
        request = payload(connector)

        first = await connector._persist(request)
        duplicate = await connector._persist(request)

        self.assertEqual(first.id, duplicate.id)
        self.assertEqual(request, first.payload())
        self.assertEqual(1, wal.pending_count())
        self.assertIsNone(repository.find_inbound_event(first.id))

    async def test_persistence_retries_the_same_frame_with_bounded_backoff(self) -> None:
        """WAL 瞬时故障期间应保留当前帧并原地重试。"""
        wal = FlakyInboundWal(2)
        connector = bridge(wal=wal)
        sleep = AsyncMock()

        with patch("mytools_onebot_connector.inbound_bridge.asyncio.sleep", sleep):
            stored = await connector._persist(payload(connector))

        self.assertIsNotNone(stored)
        self.assertEqual(3, wal.store_calls)
        sleep.assert_has_awaits([call(1), call(2)])

    async def test_persistence_stops_after_bounded_attempts(self) -> None:
        """WAL 长期不可用应终止桥接且不得形成无限重试。"""
        wal = FlakyInboundWal(PERSIST_MAX_ATTEMPTS)
        connector = bridge(wal=wal)
        sleep = AsyncMock()

        with patch("mytools_onebot_connector.inbound_bridge.asyncio.sleep", sleep):
            with self.assertRaisesRegex(RuntimeError, "persistence exhausted"):
                await connector._persist(payload(connector))

        self.assertEqual(PERSIST_MAX_ATTEMPTS, wal.store_calls)
        self.assertEqual(255, sum(item.args[0] for item in sleep.await_args_list))

    async def test_capacity_full_backpressures_current_frame_then_fails_closed(self) -> None:
        """容量满应原地背压当前帧，达到预算后停止桥接而非继续接收。"""
        wal = CapacityInboundWal()
        connector = bridge(wal=wal)
        sleep = AsyncMock()

        with patch("mytools_onebot_connector.inbound_bridge.asyncio.sleep", sleep):
            with self.assertRaises(WalPersistenceExhaustedError):
                await connector._persist(payload(connector))

        self.assertEqual(PERSIST_MAX_ATTEMPTS, wal.store_calls)
        self.assertEqual(255, sum(item.args[0] for item in sleep.await_args_list))
        self.assertEqual(0, wal.pending_count())

    async def test_database_import_failure_remains_in_wal_then_succeeds(self) -> None:
        """MySQL 瞬时故障不得删除 WAL，恢复后才可确认。"""
        repository = FlakyPersistenceRepository(2)
        wal = InMemoryInboundWal()
        connector = bridge(repository, wal)
        stored = await connector._persist(payload(connector))

        for expected_attempt in (1, 2):
            entry = wal.load_batch(datetime.now(UTC) + timedelta(days=1), 1)[0]
            await connector._import_wal_entry(entry)
            self.assertEqual(1, wal.pending_count())
            current = next(iter(wal.entries.values()))
            self.assertEqual(expected_attempt, current.attempt_count)
            self.assertIsNone(repository.find_inbound_event(stored.id))

        entry = wal.load_batch(datetime.now(UTC) + timedelta(days=1), 1)[0]
        await connector._import_wal_entry(entry)
        self.assertEqual(0, wal.pending_count())
        self.assertIsNotNone(repository.find_inbound_event(stored.id))

    async def test_database_import_exhaustion_moves_wal_to_dead(self) -> None:
        """MySQL 导入达到预算后应保留为隔离文件且停止自动重试。"""
        repository = FlakyPersistenceRepository(PERSIST_MAX_ATTEMPTS)
        wal = InMemoryInboundWal()
        connector = bridge(repository, wal)
        await connector._persist(payload(connector))

        for _ in range(PERSIST_MAX_ATTEMPTS):
            entry = wal.load_batch(datetime.now(UTC) + timedelta(days=1), 1)[0]
            await connector._import_wal_entry(entry)

        self.assertEqual(0, wal.pending_count())
        self.assertEqual(1, len(wal.dead))
        self.assertEqual("DB_IMPORT_EXHAUSTED", wal.dead[0][1])

    async def test_wal_scan_stops_after_bounded_attempts(self) -> None:
        """WAL 目录持续不可读时应让 worker 失败，避免健康假象。"""
        wal = FailingWalScan(PERSIST_MAX_ATTEMPTS)
        connector = bridge(wal=wal)
        sleep = AsyncMock()

        with patch("mytools_onebot_connector.inbound_bridge.asyncio.sleep", sleep):
            with self.assertRaisesRegex(
                    WalPersistenceExhaustedError, "WAL scan exhausted"):
                await connector._wal_import_loop()

        self.assertEqual(PERSIST_MAX_ATTEMPTS, wal.scan_calls)
        self.assertEqual(255, sum(item.args[0] for item in sleep.await_args_list))

    async def test_delivery_scan_stops_after_bounded_attempts(self) -> None:
        """数据库队列持续无法扫描时应有界失败并交由进程重启。"""
        repository = ScriptedDeliveryScanRepository(
            [False] * PERSIST_MAX_ATTEMPTS)
        connector = bridge(repository)
        sleep = AsyncMock()

        with patch("mytools_onebot_connector.inbound_bridge.asyncio.sleep", sleep):
            with self.assertRaisesRegex(RuntimeError, "spool scan exhausted"):
                await connector._delivery_loop(FakeSession([]))

        self.assertEqual(PERSIST_MAX_ATTEMPTS, repository.scan_calls)
        self.assertEqual(255, sum(item.args[0] for item in sleep.await_args_list))

    async def test_successful_delivery_scan_resets_failure_budget(self) -> None:
        """一次成功扫描后，后续故障应重新获得完整重试预算。"""
        outcomes = [False, False, True] + [False] * PERSIST_MAX_ATTEMPTS
        repository = ScriptedDeliveryScanRepository(outcomes)
        connector = bridge(repository)
        await persist_and_import(connector, payload(connector))
        sleep = AsyncMock()

        with patch("mytools_onebot_connector.inbound_bridge.asyncio.sleep", sleep):
            with self.assertRaisesRegex(RuntimeError, "spool scan exhausted"):
                await connector._delivery_loop(FakeSession([FakeResponse(200)]))

        self.assertEqual(len(outcomes), repository.scan_calls)
        self.assertEqual(
            [1, 2, 1, 2, 4, 8, 16, 32, 64, 128],
            [item.args[0] for item in sleep.await_args_list])

    async def test_rejects_same_event_key_with_different_payload(self) -> None:
        """同一渠道事件键不得被不同载荷静默覆盖。"""
        repository = InMemoryAccountRepository()
        connector = bridge(repository)
        request = payload(connector)
        await connector._persist(request)
        changed = payload(connector)
        changed["event"]["message"] = [{"type": "text", "data": {"text": "changed"}}]

        with self.assertRaisesRegex(ValueError, "idempotency conflict"):
            await connector._persist(changed)

    async def test_transient_failure_is_persisted_then_succeeds(self) -> None:
        """瞬时 5xx 应保存重试状态，后续成功后再标记完成。"""
        repository = InMemoryAccountRepository()
        connector = bridge(repository)
        await persist_and_import(connector, payload(connector))
        event = claim_due(repository)[0]

        await connector._deliver_event(FakeSession([FakeResponse(503)]), event)

        failed = repository.find_inbound_event(event.id)
        self.assertIsNotNone(failed)
        assert failed is not None
        self.assertEqual(InboundEventStatus.PENDING, failed.status)
        self.assertEqual(1, failed.attempt_count)
        self.assertEqual("HTTP_503", failed.last_error_code)
        self.assertEqual(timedelta(seconds=1), failed.next_attempt_at - failed.updated_at)

        retried = claim_due(repository)[0]
        await connector._deliver_event(FakeSession([FakeResponse(202)]), retried)
        delivered = repository.find_inbound_event(event.id)
        self.assertIsNotNone(delivered)
        assert delivered is not None
        self.assertEqual(InboundEventStatus.DELIVERED, delivered.status)
        self.assertIsNotNone(delivered.delivered_at)

    async def test_permanent_client_failure_is_dead_without_retry(self) -> None:
        """永久 4xx 应在第一次失败后直接进入 DEAD。"""
        repository = InMemoryAccountRepository()
        connector = bridge(repository)
        await persist_and_import(connector, payload(connector))
        event = claim_due(repository)[0]
        response = FakeResponse(401)

        await connector._deliver_event(FakeSession([response]), event)

        failed = repository.find_inbound_event(event.id)
        self.assertIsNotNone(failed)
        assert failed is not None
        self.assertEqual(InboundEventStatus.DEAD, failed.status)
        self.assertEqual(1, failed.attempt_count)
        self.assertEqual("HTTP_401", failed.last_error_code)
        self.assertIsNotNone(failed.dead_at)
        self.assertEqual(1, response.read_calls)

    async def test_redirect_is_not_followed_or_marked_delivered(self) -> None:
        """3xx 不能证明 Messaging 已入库，应直接进入 DEAD。"""
        repository = InMemoryAccountRepository()
        connector = bridge(repository)
        await persist_and_import(connector, payload(connector))
        event = claim_due(repository)[0]
        session = FakeSession([FakeResponse(302)])

        await connector._deliver_event(session, event)

        failed = repository.find_inbound_event(event.id)
        assert failed is not None
        self.assertEqual(InboundEventStatus.DEAD, failed.status)
        self.assertEqual("HTTP_302", failed.last_error_code)
        self.assertFalse(session.calls[0]["allow_redirects"])

    async def test_timeout_is_explicit_and_retryable(self) -> None:
        """每次 POST 都应使用有限超时，并持久化超时重试状态。"""
        repository = InMemoryAccountRepository()
        connector = bridge(repository)
        await persist_and_import(connector, payload(connector))
        event = claim_due(repository)[0]
        session = FakeSession([asyncio.TimeoutError()])

        await connector._deliver_event(session, event)

        self.assertEqual(1, len(session.calls))
        timeout = session.calls[0]["timeout"]
        self.assertIs(timeout, MESSAGING_FORWARD_TIMEOUT)
        self.assertEqual(10, timeout.total)
        self.assertEqual(2, timeout.connect)
        self.assertEqual(8, timeout.sock_read)
        self.assertFalse(session.calls[0]["allow_redirects"])
        failed = repository.find_inbound_event(event.id)
        self.assertIsNotNone(failed)
        assert failed is not None
        self.assertEqual(InboundEventStatus.PENDING, failed.status)
        self.assertEqual("TIMEOUT", failed.last_error_code)

    async def test_transient_failures_cover_restart_window_then_become_dead(self) -> None:
        """连续瞬时错误覆盖四分钟窗口后必须停止重试。"""
        repository = InMemoryAccountRepository()
        connector = bridge(repository)
        await persist_and_import(connector, payload(connector))
        session = FakeSession([FakeResponse(503) for _ in range(DELIVERY_MAX_ATTEMPTS)])
        delays: list[int] = []

        for _ in range(DELIVERY_MAX_ATTEMPTS):
            current = claim_due(repository)[0]
            await connector._deliver_event(session, current)
            updated = repository.find_inbound_event(current.id)
            self.assertIsNotNone(updated)
            assert updated is not None
            if updated.status is InboundEventStatus.PENDING:
                delays.append(int((updated.next_attempt_at - updated.updated_at).total_seconds()))
            current = updated

        self.assertEqual(DELIVERY_MAX_ATTEMPTS, len(session.calls))
        self.assertEqual(255, sum(delays))
        self.assertEqual(InboundEventStatus.DEAD, current.status)
        self.assertEqual(DELIVERY_MAX_ATTEMPTS, current.attempt_count)

    async def test_slow_delivery_does_not_block_new_event_persistence(self) -> None:
        """慢下游请求不得阻塞 WebSocket 路径继续持久化新事件。"""
        repository = InMemoryAccountRepository()
        connector = bridge(repository)
        await persist_and_import(connector, payload(connector, 1))
        first = claim_due(repository)[0]
        started = asyncio.Event()
        release = asyncio.Event()
        delivery = asyncio.create_task(
            connector._deliver_event(BlockingSession(started, release), first))
        await started.wait()

        second_event = await asyncio.wait_for(
            connector._persist(payload(connector, 2)), timeout=0.5)

        self.assertNotEqual(first.id, second_event.id)
        self.assertEqual(1, connector._inbound_wal.pending_count())
        release.set()
        await delivery

    async def test_delivery_loop_recovers_pending_event_after_restart(self) -> None:
        """新 worker 启动时应主动扫描并恢复此前已经持久化的事件。"""
        repository = InMemoryAccountRepository()
        wal = InMemoryInboundWal()
        producer = bridge(repository, wal)
        stored = await producer._persist(payload(producer))
        connector = bridge(repository, wal)
        wal_worker = asyncio.create_task(connector._wal_import_loop())
        delivery_worker = asyncio.create_task(
            connector._delivery_loop(FakeSession([FakeResponse(200)])))
        try:
            for _ in range(100):
                current = repository.find_inbound_event(stored.id)
                if current is not None and current.status is InboundEventStatus.DELIVERED:
                    break
                await asyncio.sleep(0.01)
        finally:
            wal_worker.cancel()
            delivery_worker.cancel()
            await asyncio.gather(wal_worker, delivery_worker, return_exceptions=True)

        recovered = repository.find_inbound_event(stored.id)
        self.assertIsNotNone(recovered)
        assert recovered is not None
        self.assertEqual(InboundEventStatus.DELIVERED, recovered.status)


def test_expired_lease_is_reclaimed_and_stale_token_is_fenced() -> None:
    """租约到期可恢复，但旧 worker 的迟到结果不得覆盖新状态。"""
    repository = InMemoryAccountRepository()
    connector = bridge(repository)
    stored = asyncio.run(persist_and_import(connector, payload(connector)))
    now = datetime.now(UTC) + timedelta(seconds=1)
    first = repository.claim_due_inbound_events(
        now, now + timedelta(seconds=30), 1, DELIVERY_MAX_ATTEMPTS)[0]

    assert repository.claim_due_inbound_events(
        now + timedelta(seconds=10), now + timedelta(seconds=40),
        1, DELIVERY_MAX_ATTEMPTS) == []
    second = repository.claim_due_inbound_events(
        now + timedelta(seconds=31), now + timedelta(seconds=61),
        1, DELIVERY_MAX_ATTEMPTS)[0]

    assert first.attempt_token != second.attempt_token
    assert second.attempt_count == 2
    assert repository.mark_inbound_event_delivered(
        stored.id, first.attempt_token, now + timedelta(seconds=32)) is False
    assert repository.mark_inbound_event_failed(
        stored.id, first.attempt_token, "LATE_FAILURE", now, False,
        now + timedelta(seconds=32)) is False
    assert repository.mark_inbound_event_delivered(
        stored.id, second.attempt_token, now + timedelta(seconds=32)) is True
    current = repository.find_inbound_event(stored.id)
    assert current is not None
    assert current.status is InboundEventStatus.DELIVERED


def test_repeated_worker_crashes_still_exhaust_attempt_budget() -> None:
    """每次申领后都崩溃也必须在固定次数后进入 DEAD。"""
    repository = InMemoryAccountRepository()
    connector = bridge(repository)
    stored = asyncio.run(persist_and_import(connector, payload(connector)))
    now = datetime.now(UTC) + timedelta(seconds=1)

    for _ in range(DELIVERY_MAX_ATTEMPTS):
        claimed = repository.claim_due_inbound_events(
            now, now + timedelta(seconds=30), 1, DELIVERY_MAX_ATTEMPTS)
        assert len(claimed) == 1
        now += timedelta(seconds=31)

    assert repository.claim_due_inbound_events(
        now, now + timedelta(seconds=30), 1, DELIVERY_MAX_ATTEMPTS) == []
    current = repository.find_inbound_event(stored.id)
    assert current is not None
    assert current.status is InboundEventStatus.DEAD
    assert current.attempt_count == DELIVERY_MAX_ATTEMPTS
    assert current.last_error_code == "RETRY_EXHAUSTED"
