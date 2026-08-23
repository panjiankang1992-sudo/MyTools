"""适配器 HTTP 鉴权和默认关闭门禁测试。"""

from contextlib import contextmanager
from http.server import ThreadingHTTPServer
import json
from threading import Thread
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import pytest

from mytools_msgservice_adapter.http_api import create_handler
from mytools_msgservice_adapter.repository import InMemorySnapshotRepository
from mytools_msgservice_adapter.service import SnapshotService


@contextmanager
def server(service: SnapshotService):
    """在随机本地端口启动受保护测试服务。"""
    instance = ThreadingHTTPServer(("127.0.0.1", 0), create_handler(service, "test-token"))
    thread = Thread(target=instance.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{instance.server_port}"
    finally:
        instance.shutdown()
        instance.server_close()
        thread.join(timeout=2)


def test_migration_export_requires_token() -> None:
    service = SnapshotService(InMemorySnapshotRepository(), False, True)
    with server(service) as base_url:
        with pytest.raises(HTTPError) as failure:
            urlopen(base_url + "/internal/v1/migration/inbound-messages?limit=200", timeout=2)
        assert failure.value.code == 401


def test_authorized_export_remains_unavailable_when_disabled() -> None:
    service = SnapshotService(InMemorySnapshotRepository(), False, False)
    with server(service) as base_url:
        request = Request(base_url + "/internal/v1/migration/inbound-messages?limit=200",
                          headers={"Authorization": "Bearer test-token"})
        with pytest.raises(HTTPError) as failure:
            urlopen(request, timeout=2)
        assert failure.value.code == 503
        assert json.loads(failure.value.read())["error"] == "snapshot export is disabled"
