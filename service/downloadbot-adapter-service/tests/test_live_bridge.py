"""旧 DownloadBot 只读实时桥接测试。"""

from __future__ import annotations

import hashlib
from pathlib import Path
import sys
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from mytools_downloadbot_adapter.live_bridge import BRIDGE_NAME, LegacyLinkJob, LiveBridge
from mytools_downloadbot_adapter.models import AdapterMode, EventStatus
from mytools_downloadbot_adapter.service import AdapterService, InMemoryEventRepository


class Source:
    """内存旧库页。"""

    def __init__(self, rows):
        self.rows = rows

    def page_after(self, legacy_id, limit):
        return [row for row in self.rows if row.legacy_id > legacy_id][:limit]

    def high_water(self):
        return max((row.legacy_id for row in self.rows), default=0)


class Checkpoint:
    """内存单调游标。"""

    def __init__(self):
        self.value = None
        self.rejections = []

    def current(self, bridge_name):
        assert bridge_name == BRIDGE_NAME
        return self.value

    def initialize(self, bridge_name, legacy_id):
        assert bridge_name == BRIDGE_NAME
        if self.value is None:
            self.value = legacy_id
        return self.value

    def advance(self, bridge_name, expected, legacy_id):
        assert bridge_name == BRIDGE_NAME and self.value == expected
        self.value = legacy_id

    def reject(self, bridge_name, legacy_id, reason_code, detail):
        self.rejections.append((bridge_name, legacy_id, reason_code, detail))


class Client:
    """可控新下载客户端。"""

    def __init__(self, fail=False):
        self.fail = fail
        self.commands = []

    def create_request(self, command):
        self.commands.append(command)
        if self.fail:
            raise RuntimeError("temporary failure")
        return uuid4()


def row(legacy_id: int, kind: str, uri: str, account_id: str = "") -> LegacyLinkJob:
    """创建摘要正确的旧链接行。"""
    return LegacyLinkJob(legacy_id, uri, hashlib.sha256(uri.encode()).hexdigest(), kind,
                         "PIKPAK" if kind == "MAGNET" else "LOCAL", "MESSAGE",
                         "a" * 64, account_id)


def test_maps_all_supported_link_kinds_and_advances_cursor():
    """HTTP、X 和已映射 Magnet 会转换为对应任务类型。"""
    account_id = uuid4()
    source = Source([
        row(1, "HTTP", "https://example.com/page"),
        row(2, "X_POST", "https://x.com/i/web/status/123"),
        row(3, "MAGNET", "magnet:?xt=urn:btih:" + "a" * 40, "legacy-account"),
    ])
    checkpoint = Checkpoint()
    client = Client()
    adapter = AdapterService(InMemoryEventRepository(), client, AdapterMode.SHADOW)

    handled = LiveBridge(source, checkpoint, adapter, AdapterMode.SHADOW,
                         {"legacy-account": account_id}, start_from_latest=False).poll_once()

    assert handled == 3
    assert checkpoint.value == 3
    assert [command.request_kind for command in client.commands] == [
        "WEB_ARCHIVE", "X_POST", "MAGNET"]
    assert client.commands[-1].parameters["accountId"] == str(account_id)


def test_shadow_failure_keeps_cursor_for_idempotent_retry():
    """新服务失败时事件留在收件箱且游标不越过。"""
    checkpoint = Checkpoint()
    client = Client(fail=True)
    repository = InMemoryEventRepository()
    adapter = AdapterService(repository, client, AdapterMode.SHADOW)
    bridge = LiveBridge(Source([row(1, "HTTP", "https://example.com/file")]),
                        checkpoint, adapter, AdapterMode.SHADOW, start_from_latest=False)

    assert bridge.poll_once() == 0
    assert checkpoint.value == 0
    assert repository.find_by_event_id("downloadbot-link:1").status is EventStatus.FAILED

    client.fail = False
    assert bridge.poll_once() == 1
    assert checkpoint.value == 1


def test_records_unmapped_magnet_without_losing_following_rows():
    """无法映射的账号形成拒绝证据并允许后续行继续。"""
    checkpoint = Checkpoint()
    client = Client()
    adapter = AdapterService(InMemoryEventRepository(), client, AdapterMode.DISABLED)
    source = Source([
        row(1, "MAGNET", "magnet:?xt=urn:btih:" + "b" * 40, "missing"),
        row(2, "HTTP", "https://example.com/page"),
    ])

    assert LiveBridge(source, checkpoint, adapter, AdapterMode.DISABLED,
                      start_from_latest=False).poll_once() == 2
    assert checkpoint.value == 2
    assert checkpoint.rejections[0][1:3] == (1, "UNSUPPORTED_LEGACY_LINK")
    assert client.commands == []


def test_default_initialization_skips_existing_history():
    """默认启动冻结当前高水位，防止历史链接被重新下载。"""
    checkpoint = Checkpoint()
    client = Client()
    source = Source([row(5, "HTTP", "https://example.com/history")])
    adapter = AdapterService(InMemoryEventRepository(), client, AdapterMode.SHADOW)
    bridge = LiveBridge(source, checkpoint, adapter, AdapterMode.SHADOW)

    assert bridge.poll_once() == 0
    assert checkpoint.value == 5
    assert client.commands == []

    source.rows.append(row(6, "HTTP", "https://example.com/new"))
    assert bridge.poll_once() == 1
    assert checkpoint.value == 6
