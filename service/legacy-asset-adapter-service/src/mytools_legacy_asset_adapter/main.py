"""旧资产快照适配器入口。"""

from __future__ import annotations

from http.server import ThreadingHTTPServer
import os

import pymysql
from pymysql.cursors import DictCursor

from .http_api import create_handler
from .repository import SnapshotRepository
from .service import ExportService


def main() -> None:
    """启动默认关闭的只读快照导出服务。"""
    def connection_factory():
        return pymysql.connect(
            host=os.environ.get("LEGACY_ASSET_ADAPTER_DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("LEGACY_ASSET_ADAPTER_DB_PORT", "3306")),
            user=os.environ["LEGACY_ASSET_ADAPTER_DB_USER"],
            password=os.environ["LEGACY_ASSET_ADAPTER_DB_PASSWORD"],
            database=os.environ.get("LEGACY_ASSET_ADAPTER_DB_NAME", "mytools_legacy_asset_adapter"),
            charset="utf8mb4", cursorclass=DictCursor, autocommit=True)

    enabled = os.environ.get("LEGACY_ASSET_ADAPTER_EXPORT_ENABLED", "false").lower() == "true"
    service = ExportService(SnapshotRepository(connection_factory), enabled)
    handler = create_handler(service, os.environ.get("LEGACY_ASSET_ADAPTER_INTERNAL_TOKEN", ""))
    server = ThreadingHTTPServer((os.environ.get("LEGACY_ASSET_ADAPTER_HTTP_HOST", "127.0.0.1"),
                                  int(os.environ.get("LEGACY_ASSET_ADAPTER_HTTP_PORT", "23330"))), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
