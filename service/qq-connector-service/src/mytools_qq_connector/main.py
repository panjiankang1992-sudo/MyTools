"""官方 QQ Bot Connector 进程入口。"""
from __future__ import annotations

import asyncio
import logging
import os
import signal

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
    health = web.Application()
    health.router.add_get("/health", lambda _request: web.json_response({"status": "UP"}))
    runner = web.AppRunner(health)
    await runner.setup()
    site = web.TCPSite(runner, os.getenv("QQ_CONNECTOR_HTTP_HOST", "127.0.0.1"),
                       int(os.getenv("QQ_CONNECTOR_HTTP_PORT", "23256")))
    await site.start()
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=None, connect=30,
                                                                    sock_read=None)) as session:
        task = asyncio.create_task(QQConnector(Config.load(), session).run(stop))
        await stop.wait()
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    await runner.cleanup()


def main() -> None:
    """启动 Connector。"""
    asyncio.run(run())


if __name__ == "__main__":
    main()
