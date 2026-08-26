from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4
import json

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("download_resolve_x_post", MODULE_PATH)
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_parser_accepts_only_twimg_https_resources():
    raw = json.dumps([[3, "https://pbs.twimg.com/media/a.jpg",
                       {"tweet_id": "123", "num": 1, "filename": "photo", "extension": "jpg"}],
                      [3, "https://private.invalid/a", {"tweet_id": "123"}]]).encode()
    resources = MODULE.parse_resources(raw, "123", 4)
    assert len(resources) == 1
    assert resources[0]["fileName"] == "x-123-01-photo.jpg"


class FakeContext:
    TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"}

    def __init__(self):
        self.created = []

    def create_child(self, task_name, parameters, key, **_kwargs):
        child = SimpleNamespace(id=str(uuid4()), status="QUEUED")
        self.created.append((task_name, parameters, key, child))
        return child

    def wait_child(self, child_id, _timeout):
        return SimpleNamespace(id=child_id, status="SUCCEEDED")

    def get_task(self, child_id):
        return SimpleNamespace(id=child_id, status="SUCCEEDED")

    def cancel_child(self, _child_id):
        raise AssertionError("successful children must not be cancelled")


def test_parent_creates_http_children_and_waits_for_success():
    context = FakeContext()
    request_id = str(uuid4())
    result = MODULE.execute(context, {"downloadRequestId": request_id, "ownerId": 8},
                            [{"tweetId": "123", "index": 1,
                              "url": "https://pbs.twimg.com/media/a.jpg",
                              "fileName": "a.jpg", "mimeType": "image/jpeg"}], "123")
    assert result["mediaCount"] == 1
    assert context.created[0][0] == "download_http_asset"
    assert context.created[0][1]["downloadRequestId"] == request_id
    assert context.created[0][1]["assetSourceBusinessId"].endswith(":x:123:1")


def test_parent_assigns_request_global_source_indexes():
    context = FakeContext()
    request_id = str(uuid4())
    resources = [
        {"tweetId": "123", "index": 1, "url": "https://pbs.twimg.com/media/a.jpg",
         "fileName": "a.jpg", "mimeType": "image/jpeg"},
        {"tweetId": "456", "index": 1, "url": "https://pbs.twimg.com/media/b.jpg",
         "fileName": "b.jpg", "mimeType": "image/jpeg"},
    ]

    MODULE.execute(context, {"downloadRequestId": request_id, "ownerId": 8},
                   resources, "123")

    assert [created[1]["sourceIndex"] for created in context.created] == [1, 2]
