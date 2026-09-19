"""官方 QQ Bot Connector 进程入口。"""
from __future__ import annotations

import asyncio
import logging
import os
import signal
import hmac

import aiohttp
from aiohttp import web

from .client import QQConnector
from .config import Config
from .inbound_wal import InboundEventWal, InboundWalError, InboundWalStateError
from .login_wal import LoginCommandWal, LoginWalError, LoginWalStateError
from .outbound_wal import (OutboundTextWal, OutboundWalConflictError,
                           OutboundWalDeadError, OutboundWalDeferredError,
                           OutboundWalError)

OUTBOUND_DELIVERY_TIMEOUT_SECONDS = 12.0


def positive_env(name: str, default: int) -> int:
    """读取严格正整数环境配置，并在配置错误时快速失败。"""
    raw = os.getenv(name, str(default))
    try:
        value = int(raw)
    except ValueError as exception:
        raise ValueError(f"{name} must be a positive integer") from exception
    if value < 1 or value > 1_000_000:
        raise ValueError(f"{name} must be a positive bounded integer")
    return value


def completion_page(idempotency_key: str) -> int | None:
    """从自动化完成回执幂等键中解析分页编号。"""
    page_marker = "-page-"
    if not idempotency_key.startswith("automation-completion-") \
            or page_marker not in idempotency_key:
        return None
    try:
        return int(idempotency_key.rsplit(page_marker, 1)[1])
    except ValueError:
        return None


def reply_sequence(idempotency_key: str, requested_sequence: int) -> int:
    """为同一入站消息的不同自动化回执分配稳定序号。"""
    if idempotency_key.startswith("automation-start-"):
        return 1
    if idempotency_key.startswith("automation-progress-") \
            or idempotency_key.startswith("automation-duplicate-link-"):
        return 2
    page = completion_page(idempotency_key)
    if page is not None:
        if page < 1 or page > 8:
            raise ValueError("QQ passive completion page is outside the supported range")
        return page + 2
    if idempotency_key.startswith("automation-completion-"):
        return 3
    return requested_sequence


async def deliver_text(connector: QQConnector, payload: dict,
                       outbound_wal: OutboundTextWal | None = None) -> None:
    """校验并投递一条 Messaging 路由的自动化文本回执。"""
    sender = str(payload.get("sender") or "")
    target = str(payload.get("target") or sender)
    event_type = str(payload.get("eventType") or "C2C_MESSAGE_CREATE")
    message_id = str(payload.get("messageId") or "")
    text = str(payload.get("text") or "")
    idempotency_key = str(payload.get("idempotencyKey") or "")
    page = completion_page(idempotency_key)
    active_only = idempotency_key.startswith("automation-progress-") \
            or page is not None and page > 8
    try:
        requested_sequence = int(payload.get("sequence") or 2)
        sequence = 1 if active_only else reply_sequence(idempotency_key, requested_sequence)
    except (TypeError, ValueError) as exception:
        raise web.HTTPBadRequest() from exception
    if event_type not in {"C2C_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE",
            "AT_MESSAGE_CREATE", "DIRECT_MESSAGE_CREATE"} or not target or len(target) > 512 \
            or not message_id or len(message_id) > 512 \
            or not text or len(text) > 2000 or sequence < 1 or sequence > 10:
        raise web.HTTPBadRequest()
    if not idempotency_key:
        raise web.HTTPBadRequest()
    claim = None
    if outbound_wal is not None:
        claim = await asyncio.to_thread(outbound_wal.claim, idempotency_key, payload)
        if claim is None:
            return
    try:
        async with asyncio.timeout(OUTBOUND_DELIVERY_TIMEOUT_SECONDS):
            await connector.send_text(target, message_id, text, sequence, event_type,
                                      active_only=active_only)
    except Exception as exception:
        if outbound_wal is not None and claim is not None:
            await asyncio.to_thread(outbound_wal.fail, claim,
                                    type(exception).__name__)
        raise
    if outbound_wal is not None and claim is not None:
        await asyncio.to_thread(outbound_wal.complete, claim)


async def run() -> None:
    """运行直到收到进程终止信号。"""
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for name in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(name, stop.set)
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    config = Config.load()
    login_wal = LoginCommandWal(
        config.login_wal_path,
        max_done_records=positive_env("QQ_CONNECTOR_LOGIN_DONE_MAX_RECORDS", 10_000),
        max_dead_records=positive_env("QQ_CONNECTOR_LOGIN_DEAD_MAX_RECORDS", 1_000))
    inbound_wal = InboundEventWal(
        config.inbound_wal_path,
        max_pending_records=config.inbound_max_pending_records,
        max_payload_bytes=config.inbound_max_payload_bytes,
        max_total_payload_bytes=config.inbound_max_total_payload_bytes,
        max_done_records=config.inbound_done_max_records,
        max_dead_records=config.inbound_dead_max_records)
    outbound_wal = OutboundTextWal(
        config.outbound_wal_path, max_records=config.outbound_max_records,
        max_attempts=config.outbound_max_attempts)
    session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=None, connect=30,
                                                                   sock_read=None))
    connector = QQConnector(config, session, login_wal, inbound_wal)
    health = web.Application(client_max_size=64 * 1024)

    async def health_status(_request: web.Request) -> web.Response:
        """仅在 Gateway、WAL 和登录 worker 都可用时返回健康。"""
        payload = await connector.command_health()
        outbound_stats = await asyncio.to_thread(outbound_wal.stats)
        payload["outboundWal"] = outbound_stats
        if outbound_stats["dead"] > 0:
            # 完成反馈死信必须显式阻断就绪，不能让“收得到但回不去”静默绿灯。
            payload["status"] = "DOWN"
        return web.json_response(payload, status=200 if payload["status"] == "UP" else 503)

    async def liveness(_request: web.Request) -> web.Response:
        """仅报告 HTTP 事件循环仍可响应。"""
        return web.json_response({"status": "UP"})

    health.router.add_get("/health", health_status)
    health.router.add_get("/health/live", liveness)

    async def send_text(request: web.Request) -> web.Response:
        """接收 Messaging 路由的受鉴权终态文本回执。"""
        authorization = request.headers.get("Authorization", "")
        if not hmac.compare_digest(authorization, f"Bearer {config.automation_token}"):
            raise web.HTTPUnauthorized()
        payload = await request.json()
        try:
            await deliver_text(connector, payload, outbound_wal)
        except OutboundWalConflictError as exception:
            raise web.HTTPConflict() from exception
        except OutboundWalDeadError as exception:
            raise web.HTTPUnprocessableEntity() from exception
        except OutboundWalDeferredError as exception:
            return web.json_response(
                {"status": "DEFERRED"}, status=425,
                headers={"Retry-After": str(exception.retry_after_seconds)})
        except OutboundWalError as exception:
            raise web.HTTPServiceUnavailable() from exception
        return web.json_response({"status": "SENT"})

    health.router.add_post("/internal/v1/messages/text", send_text)

    async def redrive_outbound(request: web.Request) -> web.Response:
        """按匿名稳定事件键恢复一条重试耗尽的出站消息。"""
        authorization = request.headers.get("Authorization", "")
        if not hmac.compare_digest(authorization, f"Bearer {config.automation_token}"):
            raise web.HTTPUnauthorized()
        try:
            payload = await asyncio.to_thread(
                outbound_wal.redrive, request.match_info["eventKey"])
        except (TypeError, ValueError) as exception:
            raise web.HTTPBadRequest() from exception
        except OutboundWalError as exception:
            raise web.HTTPNotFound() from exception
        try:
            await deliver_text(connector, payload, outbound_wal)
        except OutboundWalConflictError as exception:
            raise web.HTTPConflict() from exception
        except OutboundWalDeadError as exception:
            raise web.HTTPUnprocessableEntity() from exception
        except OutboundWalDeferredError as exception:
            return web.json_response(
                {"eventKey": request.match_info["eventKey"], "status": "DEFERRED"},
                status=425,
                headers={"Retry-After": str(exception.retry_after_seconds)})
        except OutboundWalError as exception:
            raise web.HTTPServiceUnavailable() from exception
        return web.json_response(
            {"eventKey": request.match_info["eventKey"], "status": "SENT"})

    health.router.add_post(
        "/internal/v1/outbound-wal/{eventKey}/redrive", redrive_outbound)

    async def redrive_inbound(request: web.Request) -> web.Response:
        """按稳定事件键恢复一条重试耗尽的入站消息。"""
        authorization = request.headers.get("Authorization", "")
        if not hmac.compare_digest(authorization, f"Bearer {config.automation_token}"):
            raise web.HTTPUnauthorized()
        try:
            entry = await asyncio.to_thread(
                inbound_wal.redrive, request.match_info["eventKey"])
        except (TypeError, ValueError) as exception:
            raise web.HTTPBadRequest() from exception
        except InboundWalStateError as exception:
            raise web.HTTPNotFound() from exception
        except InboundWalError as exception:
            raise web.HTTPServiceUnavailable() from exception
        connector._inbound_wakeup.set()
        return web.json_response(
            {"eventKey": entry.event_key, "status": "PENDING"}, status=202)

    health.router.add_post(
        "/internal/v1/inbound-wal/{eventKey}/redrive", redrive_inbound)

    async def redrive_login(request: web.Request) -> web.Response:
        """按匿名稳定事件键恢复一条死亡登录命令。"""
        authorization = request.headers.get("Authorization", "")
        if not hmac.compare_digest(authorization, f"Bearer {config.automation_token}"):
            raise web.HTTPUnauthorized()
        try:
            await asyncio.to_thread(
                login_wal.redrive, request.match_info["eventKey"])
        except (TypeError, ValueError) as exception:
            raise web.HTTPBadRequest() from exception
        except LoginWalStateError as exception:
            raise web.HTTPNotFound() from exception
        except LoginWalError as exception:
            raise web.HTTPServiceUnavailable() from exception
        connector._login_wakeup.set()
        return web.json_response(
            {"eventKey": request.match_info["eventKey"], "status": "PENDING"},
            status=202)

    async def acknowledge_login(request: web.Request) -> web.Response:
        """按匿名稳定事件键确认一条死亡登录命令。"""
        authorization = request.headers.get("Authorization", "")
        if not hmac.compare_digest(authorization, f"Bearer {config.automation_token}"):
            raise web.HTTPUnauthorized()
        try:
            await asyncio.to_thread(
                login_wal.acknowledge_dead, request.match_info["eventKey"])
        except (TypeError, ValueError) as exception:
            raise web.HTTPBadRequest() from exception
        except LoginWalStateError as exception:
            raise web.HTTPNotFound() from exception
        except LoginWalError as exception:
            raise web.HTTPServiceUnavailable() from exception
        return web.json_response(
            {"eventKey": request.match_info["eventKey"], "status": "ACKNOWLEDGED"})

    health.router.add_post(
        "/internal/v1/login-wal/{eventKey}/redrive", redrive_login)
    health.router.add_post(
        "/internal/v1/login-wal/{eventKey}/ack", acknowledge_login)
    runner = web.AppRunner(health)
    await runner.setup()
    site = web.TCPSite(runner, os.getenv("QQ_CONNECTOR_HTTP_HOST", "127.0.0.1"),
                       int(os.getenv("QQ_CONNECTOR_HTTP_PORT", "23256")))
    await site.start()
    gateway_task = asyncio.create_task(connector.run(stop), name="qq-gateway")
    login_worker_task = asyncio.create_task(
        connector.run_login_worker(stop), name="qq-login-command-worker")
    inbound_worker_task = asyncio.create_task(
        connector.run_inbound_worker(stop), name="qq-inbound-wal-worker")
    stop_task = asyncio.create_task(stop.wait(), name="qq-stop-waiter")
    try:
        completed, _ = await asyncio.wait(
            {gateway_task, login_worker_task, inbound_worker_task, stop_task},
            return_when=asyncio.FIRST_COMPLETED)
        if stop_task not in completed:
            failed = next(iter(completed))
            exception = failed.exception()
            if exception is not None:
                raise exception
            raise RuntimeError("QQ Connector background worker stopped unexpectedly")
    finally:
        for task in (gateway_task, login_worker_task, inbound_worker_task, stop_task):
            task.cancel()
        await asyncio.gather(gateway_task, login_worker_task, inbound_worker_task,
                             stop_task, return_exceptions=True)
        await session.close()
        await runner.cleanup()


def main() -> None:
    """启动 Connector。"""
    asyncio.run(run())


if __name__ == "__main__":
    main()
