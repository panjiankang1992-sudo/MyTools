from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4
from io import BytesIO
import json
import ssl
import subprocess
from urllib.error import HTTPError, URLError

import pytest

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("download_resolve_x_post", MODULE_PATH)
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def fallback_body(tweet_id="123"):
    return json.dumps({"code": 200, "tweet": {"id": tweet_id, "media": {"all": [
        {"type": "photo", "url": "https://pbs.twimg.com/media/a.jpg"}
    ]}}}).encode()


def test_fallback_prefers_explicit_direct_route_over_x_and_global_proxies(monkeypatch):
    monkeypatch.setenv("X_PROXY_URL", "http://proxy.example:8080")
    monkeypatch.setenv("HTTPS_PROXY", "http://global.example:8080")
    built = []
    opened = []

    def build(handler):
        built.append(handler.proxies)

        def open_request(request, timeout):
            opened.append((handler.proxies, request.full_url, timeout))
            return BytesIO(fallback_body())

        return SimpleNamespace(open=open_request)

    monkeypatch.setattr(MODULE, "build_opener", build)
    assert len(MODULE.fallback_resources("123", 4)) == 1
    assert built == [{}, {"http": "http://proxy.example:8080", "https": "http://proxy.example:8080"}]
    assert opened == [({}, MODULE.DEFAULT_FALLBACK_API.format(tweet_id="123"), 20)]


def test_fallback_switches_to_configured_proxy_after_direct_network_failure(monkeypatch):
    monkeypatch.setenv("X_PROXY_URL", "http://proxy.example:8080")
    routes = []

    def build(handler):
        def open_request(_request, timeout):
            routes.append(handler.proxies)
            assert 0 < timeout <= 20
            if not handler.proxies:
                raise URLError(ssl.SSLEOFError("connection interrupted"))
            return BytesIO(fallback_body())

        return SimpleNamespace(open=open_request)

    monkeypatch.setattr(MODULE, "build_opener", build)
    assert len(MODULE.fallback_resources("123", 4)) == 1
    assert routes == [{}, {"http": "http://proxy.example:8080", "https": "http://proxy.example:8080"}]


def test_fallback_retries_ssl_eof_without_exceeding_attempt_limit():
    calls = []

    def open_request(_request, timeout):
        calls.append(timeout)
        if len(calls) < 3:
            raise ssl.SSLEOFError("connection interrupted")
        return BytesIO(fallback_body())

    assert len(MODULE.fallback_resources("123", 4, opener=open_request)) == 1
    assert calls == [20, 20, 20]


@pytest.mark.parametrize("code", [429, 500, 503])
def test_fallback_retries_transient_http_errors(code):
    calls = []
    error = HTTPError("https://api.fxtwitter.com/status/123", code, "temporary", {}, None)

    def open_request(_request, timeout):
        calls.append(timeout)
        if len(calls) == 1:
            raise error
        return BytesIO(fallback_body())

    assert len(MODULE.fallback_resources("123", 4, opener=open_request)) == 1
    assert len(calls) == 2


@pytest.mark.parametrize("error", [
    HTTPError("https://api.fxtwitter.com/status/123", 404, "missing", {}, None),
    HTTPError("https://api.fxtwitter.com/status/123", 403, "forbidden", {}, None),
    URLError(ssl.SSLCertVerificationError("certificate rejected")),
])
def test_fallback_preserves_permanent_failure_without_retry(error):
    calls = []

    def open_request(_request, _timeout=None, **kwargs):
        calls.append(kwargs)
        raise error

    with pytest.raises(type(error)) as raised:
        MODULE.fallback_resources("123", 4, opener=open_request)
    assert raised.value is error
    assert len(calls) == 1


def test_fallback_network_failure_is_bounded():
    calls = []
    error = URLError("connection unavailable")

    def open_request(_request, timeout):
        calls.append(timeout)
        raise error

    with pytest.raises(URLError) as raised:
        MODULE.fallback_resources("123", 4, opener=open_request)
    assert raised.value is error
    assert calls == [20, 20, 20]


def test_fallback_wraps_exhausted_ssl_read_error_as_retryable_network_error():
    error = ssl.SSLEOFError("response interrupted")
    calls = []

    class BrokenResponse(BytesIO):
        def read(self, _limit):
            raise error

    def open_request(_request, timeout):
        calls.append(timeout)
        return BrokenResponse()

    with pytest.raises(URLError) as raised:
        MODULE.fallback_resources("123", 4, opener=open_request)
    assert raised.value.reason is error
    assert calls == [20, 20, 20]


def test_fallback_shares_total_budget_and_reduces_last_timeout(monkeypatch):
    times = iter([0, 0, 25, 55])
    monkeypatch.setattr(MODULE.time, "monotonic", lambda: next(times))
    calls = []

    def open_request(_request, timeout):
        calls.append(timeout)
        raise TimeoutError("response timed out")

    with pytest.raises(TimeoutError):
        MODULE.fallback_resources("123", 4, opener=open_request)
    assert calls == [20, 20, 5]


def test_fallback_stops_when_total_budget_is_exhausted(monkeypatch):
    times = iter([0, 0, 60])
    monkeypatch.setattr(MODULE.time, "monotonic", lambda: next(times))
    calls = []

    def open_request(_request, timeout):
        calls.append(timeout)
        raise TimeoutError("response timed out")

    with pytest.raises(TimeoutError):
        MODULE.fallback_resources("123", 4, opener=open_request)
    assert calls == [20]


@pytest.mark.parametrize("body", [b"not-json", fallback_body("456"), b"x" * (MODULE.MAX_METADATA_BYTES + 1)])
def test_fallback_never_retries_invalid_or_oversize_metadata(body):
    calls = []

    def open_request(_request, timeout):
        calls.append(timeout)
        return BytesIO(body)

    with pytest.raises(ValueError):
        MODULE.fallback_resources("123", 4, opener=open_request)
    assert calls == [20]


def test_resolver_falls_back_when_gallery_times_out(monkeypatch):
    monkeypatch.setattr(MODULE, "fallback_resources", lambda tweet_id, maximum: [{
        "tweetId": tweet_id, "index": 1, "url": "https://pbs.twimg.com/media/a.jpg",
        "fileName": "a.jpg", "mimeType": "image/jpeg"}])

    def timed_out(*_args, **_kwargs):
        raise subprocess.TimeoutExpired("gallery-dl", 90)

    tweet_id, resources = MODULE.resolve({"url": "https://x.com/example/status/123"}, runner=timed_out)
    assert tweet_id == "123"
    assert len(resources) == 1


def test_parser_accepts_only_twimg_https_resources():
    raw = json.dumps([[3, "https://pbs.twimg.com/media/a.jpg",
                       {"tweet_id": "123", "num": 1, "filename": "photo", "extension": "jpg"}],
                      [3, "https://private.invalid/a", {"tweet_id": "123"}]]).encode()
    resources = MODULE.parse_resources(raw, "123", 4)
    assert len(resources) == 1
    assert resources[0]["fileName"] == "x-123-01-photo.jpg"


def test_fallback_parser_recurses_quote_and_uses_original_photo():
    raw = json.dumps({"code": 200, "tweet": {"id": "123", "media": {"all": [
        {"id": "photo-a", "type": "photo", "url": "https://pbs.twimg.com/media/a.jpg?name=small"}
    ]}, "quote": {"id": "456", "media": {"videos": [
        {"id": "video-b", "type": "video", "url": "https://video.twimg.com/ext_tw_video/b.mp4"}
    ]}}}}).encode()
    resources = MODULE.parse_fallback_resources(raw, "123", 4)
    assert [resource["tweetId"] for resource in resources] == ["123", "456"]
    assert "name=orig" in resources[0]["url"]
    assert resources[1]["mimeType"] == "video/mp4"


def test_resolver_falls_back_when_gallery_has_no_media(monkeypatch):
    completed = SimpleNamespace(returncode=0, stdout=b"[]")
    monkeypatch.setattr(MODULE, "fallback_resources", lambda tweet_id, maximum: [{
        "tweetId": tweet_id, "index": 1, "url": "https://pbs.twimg.com/media/a.jpg",
        "fileName": "a.jpg", "mimeType": "image/jpeg"}])
    tweet_id, resources = MODULE.resolve(
        {"url": "https://x.com/example/status/123"}, runner=lambda *_args, **_kwargs: completed)
    assert tweet_id == "123"
    assert len(resources) == 1


def test_resolver_falls_back_when_gallery_binary_is_missing(monkeypatch):
    monkeypatch.setattr(MODULE, "fallback_resources", lambda tweet_id, maximum: [{
        "tweetId": tweet_id, "index": 1, "url": "https://pbs.twimg.com/media/a.jpg",
        "fileName": "a.jpg", "mimeType": "image/jpeg"}])

    def missing(*_args, **_kwargs):
        raise FileNotFoundError("gallery-dl")

    tweet_id, resources = MODULE.resolve(
        {"url": "https://x.com/example/status/123"}, runner=missing)
    assert tweet_id == "123"
    assert len(resources) == 1


class FakeContext:
    TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"}

    def __init__(self):
        self.created = []

    def create_child(self, task_name, parameters, key, **_kwargs):
        child = SimpleNamespace(id=str(uuid4()), status="QUEUED")
        self.created.append((task_name, parameters, key, child))
        return child

    def wait_child(self, child_id, _timeout, poll_seconds=1.0):
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

    MODULE.execute(context, {"downloadRequestId": request_id, "ownerId": 8,
                             "sourceIndexOffset": 200},
                   resources, "123")

    assert [created[1]["sourceIndex"] for created in context.created] == [201, 202]
