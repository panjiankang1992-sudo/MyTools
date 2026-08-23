"""OneBot Connector 进程入口。"""

from __future__ import annotations

from http.server import ThreadingHTTPServer
import os

import pymysql
from pymysql.cursors import DictCursor

from .connector import OneBotClient
from .http_api import create_handler
from .mysql_repository import MySqlAccountRepository
from .service import OneBotConnectorService


def main() -> None:
    """启动默认关闭且绑定回环地址的连接器进程。"""
    def connection_factory():
        return pymysql.connect(
            host=os.environ.get("ONEBOT_CONNECTOR_DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("ONEBOT_CONNECTOR_DB_PORT", "3306")),
            user=os.environ["ONEBOT_CONNECTOR_DB_USER"],
            password=os.environ["ONEBOT_CONNECTOR_DB_PASSWORD"],
            database=os.environ.get("ONEBOT_CONNECTOR_DB_NAME", "mytools_onebot_connector"),
            charset="utf8mb4", cursorclass=DictCursor, autocommit=False)

    service = OneBotConnectorService(
        MySqlAccountRepository(connection_factory), OneBotClient(),
        os.environ.get("ONEBOT_CONNECTOR_ENABLED", "false").lower() == "true",
        int(os.environ.get("ONEBOT_CONNECTOR_MAXIMUM_BYTES", str(20 * 1024 * 1024 * 1024))))
    handler = create_handler(service, os.environ.get("ONEBOT_CONNECTOR_INTERNAL_TOKEN", ""),
                             os.environ.get("ONEBOT_CONNECTOR_ADMIN_TOKEN", ""))
    server = ThreadingHTTPServer((os.environ.get("ONEBOT_CONNECTOR_HTTP_HOST", "127.0.0.1"),
                                  int(os.environ.get("ONEBOT_CONNECTOR_HTTP_PORT", "23255"))),
                                 handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
