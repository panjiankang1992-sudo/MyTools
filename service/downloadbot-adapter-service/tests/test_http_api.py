from http.server import ThreadingHTTPServer
import json
from threading import Thread
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from mytools_downloadbot_adapter.http_api import create_handler
from mytools_downloadbot_adapter.models import AdapterMode
from mytools_downloadbot_adapter.service import AdapterService, InMemoryEventRepository


class FakeClient:
    def create_request(self, _command):
        return uuid4()


class FakeSnapshotRepository:
    def export_page(self, snapshot_id, after_id, limit):
        return {"snapshotId": str(snapshot_id), "afterId": after_id, "limit": limit, "items": []}


def start(enabled):
    service = AdapterService(InMemoryEventRepository(), FakeClient(), AdapterMode.DISABLED)
    handler = create_handler(service, "event-token", FakeSnapshotRepository(), enabled, "test-token")
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


def request(server, snapshot_id):
    value = Request(f"http://127.0.0.1:{server.server_port}/internal/v1/migration/"
                    f"downloadbot/snapshot-items?snapshotId={snapshot_id}&limit=20",
                    headers={"Authorization": "Bearer test-token"})
    with urlopen(value, timeout=2) as response:
        return json.loads(response.read())


def test_snapshot_export_is_default_off():
    server, thread = start(False)
    try:
        try:
            request(server, uuid4())
            raise AssertionError("disabled export unexpectedly succeeded")
        except HTTPError as exception:
            assert exception.code == 503
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_snapshot_export_requires_explicit_enablement():
    server, thread = start(True)
    snapshot_id = uuid4()
    try:
        result = request(server, snapshot_id)
        assert result["snapshotId"] == str(snapshot_id)
        assert result["limit"] == 20
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
