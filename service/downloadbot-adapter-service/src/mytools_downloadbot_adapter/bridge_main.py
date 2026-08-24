"""DownloadBot 只读实时桥接独立进程入口。"""

from __future__ import annotations

import json
import os
import time
from uuid import UUID

import pymysql
from pymysql.cursors import DictCursor

from .client import DownloadIngestionHttpClient
from .live_bridge import LiveBridge, MySqlBridgeCheckpoint, MySqlLegacyLinkSource
from .models import AdapterMode, EventStatus
from .mysql_repository import MySqlEventRepository
from .service import AdapterService


def main() -> None:
    """在显式开启后持续轮询旧链接任务并投递到适配器收件箱。"""
    if os.environ.get("DOWNLOADBOT_LIVE_BRIDGE_ENABLED", "false").lower() != "true":
        raise RuntimeError("DownloadBot live bridge is disabled")

    def adapter_connection():
        return pymysql.connect(
            host=os.environ.get("DOWNLOADBOT_ADAPTER_DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("DOWNLOADBOT_ADAPTER_DB_PORT", "3306")),
            user=os.environ["DOWNLOADBOT_ADAPTER_DB_USER"],
            password=os.environ["DOWNLOADBOT_ADAPTER_DB_PASSWORD"],
            database=os.environ.get("DOWNLOADBOT_ADAPTER_DB_NAME", "mytools_downloadbot_adapter"),
            charset="utf8mb4", cursorclass=DictCursor, autocommit=False)

    def legacy_connection():
        return pymysql.connect(
            host=os.environ.get("DOWNLOADBOT_LEGACY_DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("DOWNLOADBOT_LEGACY_DB_PORT", "3306")),
            user=os.environ["DOWNLOADBOT_LEGACY_DB_USER"],
            password=os.environ["DOWNLOADBOT_LEGACY_DB_PASSWORD"],
            database=os.environ.get("DOWNLOADBOT_LEGACY_DB_NAME", "downloadbot"),
            charset="utf8mb4", cursorclass=DictCursor, autocommit=True)

    raw_mapping = json.loads(os.environ.get("DOWNLOADBOT_PIKPAK_ACCOUNT_MAPPING", "{}"))
    if not isinstance(raw_mapping, dict):
        raise ValueError("PikPak account mapping must be an object")
    account_mapping = {str(key): UUID(str(value)) for key, value in raw_mapping.items()}
    start_mode = os.environ.get("DOWNLOADBOT_LIVE_BRIDGE_START_MODE", "LATEST").upper()
    if start_mode not in {"LATEST", "BEGINNING"}:
        raise ValueError("DownloadBot live bridge start mode is invalid")
    mode = AdapterMode(os.environ.get("DOWNLOADBOT_ADAPTER_MODE", "DISABLED"))
    client = DownloadIngestionHttpClient(
        os.environ.get("DOWNLOAD_INGESTION_URL", "http://127.0.0.1:23220"),
        os.environ.get("DOWNLOAD_INGESTION_TOKEN", ""))
    event_repository = MySqlEventRepository(adapter_connection)
    adapter = AdapterService(event_repository, client, mode)
    bridge = LiveBridge(
        MySqlLegacyLinkSource(legacy_connection), MySqlBridgeCheckpoint(adapter_connection),
        adapter, mode,
        account_mapping, start_from_latest=start_mode == "LATEST")
    interval = max(1.0, min(float(os.environ.get("DOWNLOADBOT_LIVE_BRIDGE_POLL_SECONDS", "5")), 300.0))
    page_size = max(1, min(int(os.environ.get("DOWNLOADBOT_LIVE_BRIDGE_PAGE_SIZE", "100")), 500))
    while True:
        retry_failed = False
        if mode is AdapterMode.SHADOW:
            for command in event_repository.retryable(page_size):
                if adapter.accept(command).status is not EventStatus.FORWARDED:
                    retry_failed = True
                    break
        if retry_failed:
            time.sleep(interval)
            continue
        handled = bridge.poll_once(page_size)
        if handled < page_size:
            time.sleep(interval)


if __name__ == "__main__":
    main()
