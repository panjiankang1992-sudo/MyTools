"""验证零容量下载任务不能通过发布门禁。"""
import pytest
from verify_download_dispatch import DOWNLOAD_TASKS, blocked_downloads, validate_definitions


def test_enabled_zero_capacity_download_is_blocked():
    assert blocked_downloads([{"name": "download_http_asset", "enabled": True,
                               "maxConcurrency": 0}]) == ["download_http_asset"]


def test_valid_download_capacity_is_ready():
    assert blocked_downloads([{"name": "download_http_asset", "enabled": True,
                               "maxConcurrency": 8}]) == []


def test_explicitly_disabled_and_other_workloads_are_untouched():
    assert blocked_downloads([{"name": "download_http_asset", "enabled": False, "maxConcurrency": 0},
                              {"name": "video_generate", "enabled": True, "maxConcurrency": 0}]) == []


def test_missing_or_boolean_capacity_is_invalid():
    assert blocked_downloads([{"name": "download_message_attachment", "enabled": True},
        {"name": "download_storage_object", "enabled": True, "maxConcurrency": True}]) == [
            "download_message_attachment", "download_storage_object"]


@pytest.mark.parametrize("definitions", [[], {}, [None]])
def test_incomplete_or_malformed_response_is_rejected(definitions):
    with pytest.raises(ValueError):
        validate_definitions(definitions)


def test_all_download_definitions_are_present():
    validate_definitions([{"name": name} for name in DOWNLOAD_TASKS])
