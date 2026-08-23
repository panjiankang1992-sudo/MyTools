"""MsgService 历史迁移适配器进程入口。"""

from __future__ import annotations

from http.server import ThreadingHTTPServer
import os

import pymysql
from pymysql.cursors import DictCursor

from .http_api import create_handler
from .mysql_repository import MySqlSnapshotRepository
from .service import SnapshotService


def boolean_environment(name: str) -> bool:
    """只接受显式 true 开启危险能力。"""
    return os.environ.get(name, "false").lower() == "true"


def main() -> None:
    """启动默认禁止装载和导出的独立适配器。"""
    def connection_factory():
        return pymysql.connect(
            host=os.environ.get("MSGSERVICE_ADAPTER_DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("MSGSERVICE_ADAPTER_DB_PORT", "3306")),
            user=os.environ["MSGSERVICE_ADAPTER_DB_USER"],
            password=os.environ["MSGSERVICE_ADAPTER_DB_PASSWORD"],
            database=os.environ.get("MSGSERVICE_ADAPTER_DB_NAME", "mytools_msgservice_adapter"),
            charset="utf8mb4", cursorclass=DictCursor, autocommit=False)

    service = SnapshotService(MySqlSnapshotRepository(connection_factory),
                              boolean_environment("MSGSERVICE_ADAPTER_IMPORT_ENABLED"),
                              boolean_environment("MSGSERVICE_ADAPTER_EXPORT_ENABLED"))
    handler = create_handler(service, os.environ.get("MSGSERVICE_ADAPTER_INTERNAL_TOKEN", ""))
    server = ThreadingHTTPServer((os.environ.get("MSGSERVICE_ADAPTER_HTTP_HOST", "127.0.0.1"),
                                  int(os.environ.get("MSGSERVICE_ADAPTER_HTTP_PORT", "23320"))), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
