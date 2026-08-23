"""DownloadBot 适配器进程入口。"""

from __future__ import annotations

from http.server import ThreadingHTTPServer
import os

import pymysql
from pymysql.cursors import DictCursor

from .client import DownloadIngestionHttpClient
from .http_api import create_handler
from .models import AdapterMode
from .mysql_repository import MySqlEventRepository
from .service import AdapterService
from .snapshot_repository import MySqlSnapshotRepository
from .pikpak_config import LegacyPikPakConfigExporter


def main() -> None:
    """启动默认关闭的旁路适配器。"""
    def connection_factory():
        return pymysql.connect(
            host=os.environ.get("DOWNLOADBOT_ADAPTER_DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("DOWNLOADBOT_ADAPTER_DB_PORT", "3306")),
            user=os.environ["DOWNLOADBOT_ADAPTER_DB_USER"],
            password=os.environ["DOWNLOADBOT_ADAPTER_DB_PASSWORD"],
            database=os.environ.get("DOWNLOADBOT_ADAPTER_DB_NAME", "mytools_downloadbot_adapter"),
            charset="utf8mb4", cursorclass=DictCursor, autocommit=False)

    repository = MySqlEventRepository(connection_factory)
    snapshot_repository = MySqlSnapshotRepository(connection_factory)
    client = DownloadIngestionHttpClient(
        os.environ.get("DOWNLOAD_INGESTION_URL", "http://127.0.0.1:23220"),
        os.environ.get("DOWNLOAD_INGESTION_TOKEN", ""))
    mode = AdapterMode(os.environ.get("DOWNLOADBOT_ADAPTER_MODE", "DISABLED"))
    legacy_config_path = os.environ.get("DOWNLOADBOT_LEGACY_CONFIG_PATH", "")
    pikpak_exporter = LegacyPikPakConfigExporter(legacy_config_path) if legacy_config_path else None
    handler = create_handler(
        AdapterService(repository, client, mode),
        os.environ.get("DOWNLOADBOT_ADAPTER_INTERNAL_TOKEN", ""), snapshot_repository,
        os.environ.get("DOWNLOADBOT_SNAPSHOT_EXPORT_ENABLED", "false").lower() == "true",
        os.environ.get("DOWNLOADBOT_SNAPSHOT_EXPORT_TOKEN", ""),
        os.environ.get("DOWNLOADBOT_RECONCILIATION_ENABLED", "false").lower() == "true",
        pikpak_exporter,
        os.environ.get("DOWNLOADBOT_PIKPAK_EXPORT_ENABLED", "false").lower() == "true",
        os.environ.get("DOWNLOADBOT_PIKPAK_EXPORT_TOKEN", ""))
    server = ThreadingHTTPServer((os.environ.get("DOWNLOADBOT_ADAPTER_HTTP_HOST", "127.0.0.1"),
                                  int(os.environ.get("DOWNLOADBOT_ADAPTER_HTTP_PORT", "23221"))), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
