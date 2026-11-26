"""Download Ingestion service process entrypoint."""

from __future__ import annotations

import os
from http.server import ThreadingHTTPServer

import pymysql
from pymysql.cursors import DictCursor

from .http_api import create_handler
from .migration import LegacyHistoryMigrationService
from .mysql_repository import MySqlDownloadRequestRepository
from .scheduler_client import TaskSchedulerHttpClient
from .service import DownloadRequestService


def main() -> None:
    """Start the download ingestion HTTP service."""
    def connection_factory():
        return pymysql.connect(
            host=os.environ.get("DOWNLOAD_DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("DOWNLOAD_DB_PORT", "3306")),
            user=os.environ["DOWNLOAD_DB_USER"],
            password=os.environ["DOWNLOAD_DB_PASSWORD"],
            database=os.environ.get("DOWNLOAD_DB_NAME", "mytools_download"),
            charset="utf8mb4",
            cursorclass=DictCursor,
            autocommit=False,
        )

    background_enabled = os.getenv('DOWNLOAD_BACKGROUND_ENABLED', 'false').lower() == 'true'
    repository = MySqlDownloadRequestRepository(connection_factory, background_enabled=background_enabled)
    scheduler = TaskSchedulerHttpClient(
        os.environ.get("TASK_SCHEDULER_URL", "http://127.0.0.1:23410"),
        service_id="download-ingestion-service",
        business_token=os.environ.get("TASK_BUSINESS_DOWNLOAD_TOKEN",
                                      os.environ.get("DOWNLOAD_INTERNAL_TOKEN", "")),
    )
    service = DownloadRequestService(repository, scheduler)
    app_tools = None
    if os.environ.get('DOWNLOAD_APP_TOOLS_ENABLED', 'false').lower() == 'true':
        from pathlib import Path
        from mytools_task_sdk.storage import StorageGatewayClient
        from .app_tools import AppDownloadTools
        app_tools = AppDownloadTools(service, Path(os.environ['DOWNLOAD_APP_UPLOAD_DIR']),
                                    StorageGatewayClient(os.environ['STORAGE_GATEWAY_URL'],
                                                         os.environ['STORAGE_GATEWAY_TOKEN']))
    handler = create_handler(service, repository,
                             os.environ.get("DOWNLOAD_INTERNAL_TOKEN", ""),
                             LegacyHistoryMigrationService(repository), app_tools)
    server = ThreadingHTTPServer((os.environ.get("DOWNLOAD_HTTP_HOST", "127.0.0.1"),
                                  int(os.environ.get("DOWNLOAD_HTTP_PORT", "23220"))), handler)
    worker = None
    if background_enabled:
        from .background import DownloadBackgroundWorker
        worker = DownloadBackgroundWorker(repository, service, scheduler,
            os.getenv('MESSAGING_SERVICE_URL', 'http://127.0.0.1:23250'),
            os.getenv('MESSAGING_INTERNAL_TOKEN', ''),
            enrichment_paused=os.getenv('DOWNLOAD_ENRICHMENT_PAUSED', 'false').lower() == 'true')
        worker.start()
    try:
        server.serve_forever()
    finally:
        if worker:
            worker.stop()
        server.server_close()


if __name__ == "__main__":
    main()
