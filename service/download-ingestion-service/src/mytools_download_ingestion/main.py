"""Download Ingestion service process entrypoint."""

from __future__ import annotations

from http.server import ThreadingHTTPServer
import os

import pymysql
from pymysql.cursors import DictCursor

from .http_api import create_handler
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

    repository = MySqlDownloadRequestRepository(connection_factory)
    scheduler = TaskSchedulerHttpClient(os.environ.get("TASK_SCHEDULER_URL", "http://127.0.0.1:23210"))
    handler = create_handler(DownloadRequestService(repository, scheduler), repository,
                             os.environ.get("DOWNLOAD_INTERNAL_TOKEN", ""))
    server = ThreadingHTTPServer((os.environ.get("DOWNLOAD_HTTP_HOST", "127.0.0.1"),
                                  int(os.environ.get("DOWNLOAD_HTTP_PORT", "23220"))), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
