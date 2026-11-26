import importlib.util
import json
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_register_downloaded_item", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        return None

    def read(self):
        return b'{"id":"00000000-0000-4000-8000-000000000001","version":1}'


def context(mime_type="image/jpeg", storage_uri="storage://media/yuyutian/media/202609/20260901/a.jpg"):
    return {"taskInstanceId": "00000000-0000-4000-8000-000000000010",
            "parameters": {"ownerId": 7, "downloadRequestId": "request-1",
                           "assetMimeType": mime_type},
            "stepOutputs": {"publish_asset": {"storageUri": storage_uri,
                "fileName": "a.jpg", "contentSha256": "a" * 64, "sizeBytes": 10},
                "register_asset": {"assetId": "00000000-0000-4000-8000-000000000011"}}}


def test_registers_managed_media_in_day_directory():
    captured = {}

    def requester(request, timeout):
        captured["url"] = request.full_url
        captured["body"] = json.loads(request.data)
        assert timeout == 30
        return Response()

    result = MODULE.execute(context(), "http://media", "token", requester)
    assert result == {"registered": True,
                      "mediaItemId": "00000000-0000-4000-8000-000000000001",
                      "directoryName": "20260901", "version": 1}
    assert captured["url"].endswith("/internal/v1/media/downloaded-asset-events")
    assert captured["body"]["parentDirectoryName"] == "202609"
    assert captured["body"]["directoryName"] == "20260901"
    assert len(captured["body"]["directoryKey"]) == 24


def test_skips_non_media_download():
    assert MODULE.execute(context("application/pdf"), "http://media", "") == {
        "registered": False, "reason": "not-media"}


def test_skips_media_outside_managed_date_directory():
    assert MODULE.execute(context(storage_uri="storage://media/yuyutian/other/a.jpg"),
                          "http://media", "") == {
        "registered": False, "reason": "not-managed-media-directory"}


def test_video_schedules_idempotent_preview_and_image_does_not():
    calls = []
    def requester(request, timeout):
        calls.append((request.full_url, json.loads(request.data)))
        return Response()
    MODULE.execute(context("video/mp4"), "http://media", "token", requester)
    assert len(calls) == 2
    assert calls[1][0].endswith("/analysis-operations?ownerId=7")
    assert calls[1][1] == {"idempotencyKey":"download-preview:00000000-0000-4000-8000-000000000001",
                         "analysisVersion":"download-preview-v1","frameCount":1,"seekSeconds":0}
    first = calls[1]
    MODULE.execute(context("video/mp4"), "http://media", "token", requester)
    assert calls[3] == first
    calls.clear()
    MODULE.execute(context(), "http://media", "token", requester)
    assert len(calls) == 1


def test_preview_submission_failure_is_retryable():
    calls = []
    def requester(request, timeout):
        calls.append(request.full_url)
        if len(calls) == 2:
            raise OSError("scheduler unavailable")
        return Response()
    import pytest
    with pytest.raises(OSError):
        MODULE.execute(context("video/mp4"), "http://media", "token", requester)
