"""Tests for the download tag result callback package."""
import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("download_record_tags", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_maps_generated_tags_to_terminal_result():
    context = {"parameters": {"itemId": "item-1"}, "stepOutputs": {
        "generate_tags": {"tags": [{"name": "cosplay", "type": "topic", "confidence": 0.98}]}}}
    assert MODULE.build_result(context) == {"itemId": "item-1", "tagStatus": "TAGGED",
                                             "tags": [{"name": "cosplay", "type": "topic",
                                                       "confidence": 0.98}]}


def test_records_failed_tagging_without_losing_download():
    assert MODULE.build_result({"parameters": {"itemId": "item-2"}, "stepOutputs": {}}) == {
        "itemId": "item-2", "tagStatus": "FAILED", "tags": []}


def test_projects_only_registered_successful_media_tags_and_retries_errors():
    import json
    import io
    import pytest
    context = {"taskInstanceId": "task-one", "parameters": {"ownerId": 7}, "stepOutputs": {
        "register_media_item": {"registered": True}, "register_asset": {"assetId": "asset-one"}}}
    result = {"tagStatus": "TAGGED", "tags": [{"name": "nature", "type": "topic", "confidence": .9}]}
    calls = []
    def requester(request, timeout):
        calls.append(json.loads(request.data))
        return io.BytesIO(b'{}')
    MODULE.sync_media_tags(context, result, "http://media", "token", requester)
    MODULE.sync_media_tags(context, result, "http://media", "token", requester)
    assert calls[0] == calls[1] == {"eventId": "download-tags:task-one", "ownerId": 7,
        "assetId": "asset-one", "tags": [{"name": "nature", "confidence": .9}]}
    MODULE.sync_media_tags(context, {"tagStatus": "FAILED", "tags": []}, "http://media", "", requester)
    assert len(calls) == 2
    def unavailable(request, timeout):
        raise OSError("temporary failure")
    with pytest.raises(OSError):
        MODULE.sync_media_tags(context, result, "http://media", "token", unavailable)
    context["stepOutputs"]["register_media_item"] = {"registered": False}
    MODULE.sync_media_tags(context, result, "http://media", "", requester)
    assert len(calls) == 2


def test_executor_exposes_media_credentials_only_to_tag_projection_package():
    """回归校验脚本隔离配置，防止标签已保存但同步媒体库缺少鉴权。"""
    import re
    config = (Path(__file__).resolve().parents[5] / 'task-executor-service' /
              'src/main/resources/application.yml').read_text()
    block = re.search(r'(?m)^    download_record_tags:\n((?:      .*\n)*)', config)
    assert block is not None
    assert 'MEDIA_LIBRARY_URL: ${MEDIA_LIBRARY_URL:http://127.0.0.1:23300}' in block.group(1)
    assert 'MEDIA_LIBRARY_INTERNAL_TOKEN: ${MEDIA_LIBRARY_INTERNAL_TOKEN:}' in block.group(1)
    download = re.search(r'(?m)^    download_http_asset:\n((?:      .*\n)*)', config)
    assert 'MEDIA_LIBRARY_INTERNAL_TOKEN' not in download.group(1)
