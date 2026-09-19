"""OneBot Connector 进程入口。"""

from __future__ import annotations

import asyncio
from http.server import ThreadingHTTPServer
import logging
import os
from pathlib import Path
import stat
import threading

import pymysql
from pymysql.cursors import DictCursor

from .connector import OneBotClient
from .http_api import create_handler
from .inbound_bridge import OneBotInboundBridge
from .inbound_wal import InboundEventWal
from .mysql_repository import MySqlAccountRepository
from .service import OneBotConnectorService


def read_inbound_health(connection_factory, wal_root: Path) -> dict[str, int]:
    """读取不含消息载荷的 WAL 和数据库健康计数。"""
    dead_root = wal_root / "dead"
    if dead_root.is_symlink():
        raise RuntimeError("OneBot inbound WAL dead path is invalid")
    wal_dead_events = 0
    if dead_root.exists():
        if not dead_root.is_dir():
            raise RuntimeError("OneBot inbound WAL dead path is invalid")
        # 只统计普通隔离文件，不读取文件名或正文。
        wal_dead_events = sum(
            1 for path in dead_root.iterdir()
            if stat.S_ISREG(path.stat(follow_symlinks=False).st_mode))
    connection = connection_factory()
    try:
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT
                  (SELECT COUNT(*) FROM onebot_inbound_event
                    WHERE status IN ('PENDING','IN_FLIGHT')) AS databasePendingInboundEvents,
                  (SELECT COUNT(*) FROM onebot_inbound_event
                    WHERE status='DEAD') AS databaseDeadInboundEvents,
                  (SELECT COUNT(*) FROM onebot_text_reply
                    WHERE status IN ('PREPARED','IN_FLIGHT')) AS pendingTextReplies,
                  (SELECT COUNT(*) FROM onebot_text_reply
                    WHERE status='FAILED') AS failedTextReplies,
                  (SELECT COUNT(*) FROM onebot_text_reply
                    WHERE status='UNCERTAIN') AS uncertainTextReplies
                """)
            row = cursor.fetchone()
        if not isinstance(row, dict):
            raise RuntimeError("OneBot inbound health query returned no row")
        return {
            "walDeadEvents": wal_dead_events,
            "databasePendingInboundEvents": row["databasePendingInboundEvents"],
            "databaseDeadInboundEvents": row["databaseDeadInboundEvents"],
            "pendingTextReplies": row["pendingTextReplies"],
            "failedTextReplies": row["failedTextReplies"],
            "uncertainTextReplies": row["uncertainTextReplies"],
        }
    finally:
        connection.close()


def run_inbound_bridge(bridge: OneBotInboundBridge, fatal_exit=None) -> None:
    """运行桥接；后台线程异常时终止主进程以触发 systemd 健康恢复。"""
    try:
        asyncio.run(bridge.run())
    except Exception as exception:  # noqa: BLE001
        logging.getLogger(__name__).critical(
            "OneBot inbound bridge failed errorCode=%s", type(exception).__name__)
        (fatal_exit or os._exit)(1)


def main() -> None:
    """启动默认关闭且绑定回环地址的连接器进程。"""
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")

    def connection_factory():
        return pymysql.connect(
            host=os.environ.get("ONEBOT_CONNECTOR_DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("ONEBOT_CONNECTOR_DB_PORT", "3306")),
            user=os.environ["ONEBOT_CONNECTOR_DB_USER"],
            password=os.environ["ONEBOT_CONNECTOR_DB_PASSWORD"],
            database=os.environ.get("ONEBOT_CONNECTOR_DB_NAME", "mytools_onebot_connector"),
            charset="utf8mb4", cursorclass=DictCursor, autocommit=False,
            init_command="SET time_zone = '+00:00'", connect_timeout=3,
            read_timeout=10, write_timeout=10)

    repository = MySqlAccountRepository(connection_factory)
    service = OneBotConnectorService(
        repository, OneBotClient(),
        os.environ.get("ONEBOT_CONNECTOR_ENABLED", "false").lower() == "true",
        int(os.environ.get("ONEBOT_CONNECTOR_MAXIMUM_BYTES", str(20 * 1024 * 1024 * 1024))),
        os.environ.get("ONEBOT_CONNECTOR_RELOGIN_REQUEST_PATH"),
        os.environ.get("ONEBOT_CONNECTOR_QR_PATH"),
        int(os.environ.get("ONEBOT_CONNECTOR_MAXIMUM_QR_BYTES", str(2 * 1024 * 1024))))
    bridge = None
    health_provider = None
    if os.environ.get("MESSAGING_ONEBOT_INGRESS_ENABLED", "false").lower() == "true":
        wal_path = Path(os.getenv(
            "ONEBOT_CONNECTOR_INBOUND_WAL_PATH",
            "/opt/yuyutian/mytools/runtime/onebot/inbound-wal"))
        inbound_wal = InboundEventWal(
            wal_path,
            max_pending_records=int(os.getenv(
                "ONEBOT_CONNECTOR_INBOUND_MAX_PENDING_RECORDS", "10000")),
            max_total_payload_bytes=int(os.getenv(
                "ONEBOT_CONNECTOR_INBOUND_MAX_TOTAL_PAYLOAD_BYTES",
                str(128 * 1024 * 1024))),
            max_dead_records=int(os.getenv(
                "ONEBOT_CONNECTOR_INBOUND_DEAD_MAX_RECORDS", "1000")))
        bridge = OneBotInboundBridge(
            repository,
            os.getenv("ONEBOT_CONNECTOR_ACCOUNT_KEY",
                      os.getenv("QQ_CONNECTOR_ONEBOT_ACCOUNT_KEY", "qq-napcat")),
            int(os.getenv("ONEBOT_CONNECTOR_OWNER_ID")
                or os.getenv("QQ_CONNECTOR_OWNER_ID") or "0"),
            os.getenv("ONEBOT_CONNECTOR_WS_URL", "ws://127.0.0.1:3001"),
            os.getenv("ONEBOT_CONNECTOR_MESSAGING_URL", "http://127.0.0.1:23250"),
            os.environ["MESSAGING_INTERNAL_TOKEN"],
            inbound_wal,
            health_probe=lambda: read_inbound_health(connection_factory, wal_path))
        health_provider = bridge.health_snapshot
    handler = create_handler(service, os.environ.get("ONEBOT_CONNECTOR_INTERNAL_TOKEN", ""),
                             os.environ.get("ONEBOT_CONNECTOR_ADMIN_TOKEN", ""),
                             health_provider)
    server = ThreadingHTTPServer((os.environ.get("ONEBOT_CONNECTOR_HTTP_HOST", "127.0.0.1"),
                                  int(os.environ.get("ONEBOT_CONNECTOR_HTTP_PORT", "23255"))),
                                 handler)
    if bridge is not None:
        threading.Thread(target=lambda: run_inbound_bridge(bridge),
                         name="onebot-inbound-bridge", daemon=True).start()
    server.serve_forever()


if __name__ == "__main__":
    main()
