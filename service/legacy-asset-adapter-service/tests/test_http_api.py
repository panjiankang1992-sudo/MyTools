from contextlib import contextmanager
from http.server import ThreadingHTTPServer
from threading import Thread
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import pytest

from mytools_legacy_asset_adapter.http_api import create_handler
from mytools_legacy_asset_adapter.service import ExportService


class Repository:
    def page(self, _snapshot_id, _after_sequence, _limit):
        return []


@contextmanager
def server(enabled):
    instance = ThreadingHTTPServer(("127.0.0.1", 0),
                                   create_handler(ExportService(Repository(), enabled), "token"))
    thread = Thread(target=instance.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{instance.server_port}"
    finally:
        instance.shutdown()
        instance.server_close()
        thread.join(timeout=2)


def test_export_requires_internal_token():
    with server(True) as base_url:
        with pytest.raises(HTTPError) as failure:
            urlopen(base_url + "/internal/v1/migration/assets?snapshotId=snapshot-1", timeout=2)
        assert failure.value.code == 401


def test_authorized_export_remains_default_off():
    with server(False) as base_url:
        request = Request(base_url + "/internal/v1/migration/assets?snapshotId=snapshot-1",
                          headers={"Authorization": "Bearer token"})
        with pytest.raises(HTTPError) as failure:
            urlopen(request, timeout=2)
        assert failure.value.code == 503
