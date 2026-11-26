"""Telegram Connector 进程入口。"""
from __future__ import annotations

import asyncio
import hmac
import logging
import os
import signal

import aiohttp
from aiohttp import web

from .client import TelegramConnector
from .config import Config


async def run() -> None:
    """运行 Connector 和内部 HTTP 接口。"""
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for name in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(name, stop.set)
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    config = Config.load()
    session = aiohttp.ClientSession()
    connector = TelegramConnector(config, session)
    app = web.Application(client_max_size=64 * 1024)

    def authorize(request: web.Request) -> None:
        authorization = request.headers.get("Authorization", "")
        if not hmac.compare_digest(authorization, f"Bearer {config.internal_token}"):
            raise web.HTTPUnauthorized()

    async def liveness(_request: web.Request) -> web.Response:
        """返回进程存活状态。"""
        return web.json_response({"status": "UP"})

    async def health(_request: web.Request) -> web.Response:
        """返回轮询和入站投递就绪状态。"""
        payload = connector.health()
        return web.json_response(payload, status=200 if payload["status"] == "UP" else 503)

    async def send_text(request: web.Request) -> web.Response:
        """接收 Messaging 的原会话回复。"""
        authorize(request)
        payload = await request.json()
        chat_id = str(payload.get("chatId") or "")
        message_id = int(payload.get("messageId") or 0)
        text = str(payload.get("text") or "")
        await connector.send_text(chat_id, message_id, text)
        return web.json_response({"status": "SENT"})

    async def resolve(request: web.Request) -> web.Response:
        """将 Telegram 文件引用解析为受控流。"""
        authorize(request)
        payload = await request.json()
        if payload.get("channelType") != "TELEGRAM" \
                or payload.get("accountKey") != config.account_key \
                or not str(payload.get("providerFileId") or ""):
            raise web.HTTPBadRequest()
        return web.json_response({"mode": "STREAM", "downloadUrl": None})

    async def business_status(request: web.Request) -> web.Response:
        """返回超大文件中继所需的Business连接状态。"""
        authorize(request)
        return web.json_response(connector.business_status())

    async def content(request: web.Request) -> web.StreamResponse:
        """代理 Telegram 文件内容且不暴露 Bot token。"""
        authorize(request)
        payload = await request.json()
        if payload.get("channelType") != "TELEGRAM" \
                or payload.get("accountKey") != config.account_key:
            raise web.HTTPBadRequest()
        headers, chunks = await connector.file_stream(str(payload.get("providerFileId") or ""))
        response = web.StreamResponse(headers=headers)
        await response.prepare(request)
        async for chunk in chunks:
            await response.write(chunk)
        await response.write_eof()
        return response

    app.router.add_get("/health", health)
    app.router.add_get("/health/live", liveness)
    app.router.add_post("/internal/v1/messages/text", send_text)
    app.router.add_post("/internal/v1/provider-files/resolve", resolve)
    app.router.add_post("/internal/v1/provider-files/content", content)
    app.router.add_get("/internal/v1/business-connection", business_status)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, os.getenv("TELEGRAM_CONNECTOR_HTTP_HOST", "127.0.0.1"),
                       int(os.getenv("TELEGRAM_CONNECTOR_HTTP_PORT", "23257")))
    await site.start()
    tasks = [asyncio.create_task(connector.run(stop)),
             asyncio.create_task(connector.deliver_pending(stop)), asyncio.create_task(stop.wait())]
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.result()
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await session.close()
        await runner.cleanup()
        connector.inbox.close()


def main() -> None:
    """启动 Telegram Connector。"""
    asyncio.run(run())


if __name__ == "__main__":
    main()
