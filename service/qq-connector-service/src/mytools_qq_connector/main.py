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


async def run() -> None:
    """运行直到收到进程终止信号。"""
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for name in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(name, stop.set)
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    config = Config.load()
    session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=None, connect=30,
                                                                   sock_read=None))
    connector = QQConnector(config, session)
    health = web.Application(client_max_size=64 * 1024)
    health.router.add_get("/health", lambda _request: web.json_response({"status": "UP"}))

    async def send_text(request: web.Request) -> web.Response:
        """接收 Messaging 路由的受鉴权终态文本回执。"""
        authorization = request.headers.get("Authorization", "")
        if not hmac.compare_digest(authorization, f"Bearer {config.automation_token}"):
            raise web.HTTPUnauthorized()
        payload = await request.json()
        sender = str(payload.get("sender") or "")
        target = str(payload.get("target") or sender)
        event_type = str(payload.get("eventType") or "C2C_MESSAGE_CREATE")
        message_id = str(payload.get("messageId") or "")
        text = str(payload.get("text") or "")
        sequence = int(payload.get("sequence") or 2)
        if sender != config.allowed_sender or event_type not in {"C2C_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE",
                "AT_MESSAGE_CREATE", "DIRECT_MESSAGE_CREATE"} or not target or len(target) > 512 \
                or not message_id or len(message_id) > 512 \
                or not text or len(text) > 2000 or sequence < 1 or sequence > 10:
            raise web.HTTPBadRequest()
        await connector.send_text(target, message_id, text, sequence, event_type)
        return web.json_response({"status": "SENT"})

    health.router.add_post("/internal/v1/messages/text", send_text)
    runner = web.AppRunner(health)
    await runner.setup()
    site = web.TCPSite(runner, os.getenv("QQ_CONNECTOR_HTTP_HOST", "127.0.0.1"),
                       int(os.getenv("QQ_CONNECTOR_HTTP_PORT", "23256")))
    await site.start()
    try:
        task = asyncio.create_task(connector.run(stop))
        await stop.wait()
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    finally:
        await session.close()
        await runner.cleanup()


def main() -> None:
    """启动 Connector。"""
    asyncio.run(run())


if __name__ == "__main__":
    main()
