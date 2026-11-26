"""Telegram Connector 单元测试。"""
from __future__ import annotations

import asyncio
from dataclasses import replace
from unittest.mock import patch

from mytools_telegram_connector.client import ProviderError, TelegramConnector
from mytools_telegram_connector.config import Config


class RecordingConnector(TelegramConnector):
    """记录标准化消息且不访问网络。"""

    def __init__(self, inbox_path: str = ":memory:") -> None:
        config = Config("telegram_main", "secret", 7, frozenset({"42"}),
                        "http://telegram.test", None, "http://messaging.test", "message-token",
                        "internal-token", 45, 1024 * 1024, "@pikpak_bot", 20 * 1024 * 1024, "")
        super().__init__(replace(config, inbox_path=inbox_path), object())  # type: ignore[arg-type]
        self.payload = None
        self.payloads = []
        self.api_calls = []
        self.updates = []
        self.failures = []

    async def receive(self, update: dict) -> None:
        """模拟轮询接收单条更新，并处理可投递记录。"""
        self.updates.append([update])
        await self.poll_once()
        await self.deliver_once()

    async def _internal_json(self, url: str, payload: dict) -> dict:
        if self.failures:
            raise self.failures.pop(0)
        self.payload = payload
        self.payloads.append(payload)
        return {"id": "message-id"}

    async def api(self, method: str, payload: dict | None = None):
        if method == "getUpdates":
            return self.updates.pop(0)
        self.api_calls.append((method, payload))
        return {"message_id": 99}


def test_normalizes_hidden_link_and_largest_photo() -> None:
    """隐藏链接和最大照片应进入同一标准消息。"""
    connector = RecordingConnector()
    asyncio.run(connector.receive({"update_id": 9, "message": {
        "message_id": 12, "date": 1_700_000_000, "chat": {"id": 42},
        "from": {"id": 99}, "caption": "download",
        "caption_entities": [{"type": "text_link", "url": "https://example.test/item"}],
        "photo": [{"file_id": "small", "file_size": 10},
                  {"file_id": "large", "file_size": 20}]}}))

    assert connector.payload is not None
    assert connector.payload["channelType"] == "TELEGRAM"
    assert connector.payload["externalMessageId"] == "telegram_main:42:12"
    assert connector.payload["body"] == "download\nhttps://example.test/item"
    attachment = connector.payload["parts"][-1]
    assert attachment["providerFileId"] == "large"
    assert attachment["sourceUrl"] is None


def test_rejects_unapproved_chat() -> None:
    """非白名单会话不得写入 Messaging。"""
    connector = RecordingConnector()
    asyncio.run(connector.receive({"update_id": 10, "message": {
        "message_id": 13, "chat": {"id": 43}, "text": "ignored"}}))
    assert connector.payload is None


def test_empty_allowlist_preserves_legacy_allow_all_semantics() -> None:
    """旧配置空白名单应继续接收全部会话。"""
    connector = RecordingConnector()
    object.__setattr__(connector.config, "allowed_chat_ids", frozenset())
    asyncio.run(connector.receive({"update_id": 11, "message": {
        "message_id": 14, "chat": {"id": 43}, "text": "accepted"}}))
    assert connector.payload is not None


def test_expands_bounded_reply_chain_with_attachments() -> None:
    """引用消息中的文本和附件应与当前消息一并标准化。"""
    connector = RecordingConnector()
    asyncio.run(connector.receive({"update_id": 12, "message": {
        "message_id": 20, "chat": {"id": 42}, "from": {"id": 99}, "text": "current",
        "reply_to_message": {"message_id": 19, "text": "quoted",
            "document": {"file_id": "quoted-file", "file_name": "quoted.zip",
                         "mime_type": "application/zip", "file_size": 50}}}}))
    assert connector.payload is not None
    assert connector.payload["body"] == "quoted\ncurrent"
    assert [part["providerFileId"] for part in connector.payload["parts"]
            if part["type"] == "ATTACHMENT"] == ["quoted-file"]


def test_video_sticker_keeps_video_semantics() -> None:
    """视频贴纸不得弱化成普通文件。"""
    connector = RecordingConnector()
    asyncio.run(connector.receive({"update_id": 13, "message": {
        "message_id": 21, "chat": {"id": 42},
        "sticker": {"file_id": "video-sticker", "is_video": True,
                    "mime_type": "video/webm"}}}))
    assert connector.payload["parts"][0]["attachmentType"] == "VIDEO"


def test_groups_media_album_into_one_inbound_message() -> None:
    """同一 media_group_id 的多个更新应形成一条稳定入站消息。"""
    async def scenario() -> RecordingConnector:
        connector = RecordingConnector()
        for message_id, file_id in ((31, "photo-a"), (32, "photo-b")):
            await connector.receive({"update_id": message_id, "message": {
                "message_id": message_id, "date": 1_700_000_000, "chat": {"id": 42},
                "from": {"id": 99}, "media_group_id": "album-7",
                "photo": [{"file_id": file_id, "file_size": 20}]}})
        assert connector.payload is None
        with patch("mytools_telegram_connector.inbox.time.time", return_value=2_000_000_000):
            assert await connector.deliver_once()
        return connector

    connector = asyncio.run(scenario())
    assert len(connector.payloads) == 1
    assert connector.payload["externalMessageId"] == "telegram_main:42:31:album:album-7"
    assert [part["providerFileId"] for part in connector.payload["parts"]] == ["photo-a", "photo-b"]
    assert [part["fileName"] for part in connector.payload["parts"]] == [
        "telegram-image-31-1", "telegram-image-32-1"]
    assert [part["mimeType"] for part in connector.payload["parts"]] == ["image/jpeg", "image/jpeg"]


def test_captures_enabled_business_connection() -> None:
    """具备回复权限的Business连接应进入就绪状态。"""
    connector = RecordingConnector()
    asyncio.run(connector.receive({"update_id": 40, "business_connection": {
        "id": "business-7", "is_enabled": True, "rights": {"can_reply": True}}}))
    assert connector.business_status() == {"connected": True, "connectionId": "business-7"}


def test_oversized_media_is_relayed_without_messaging_download() -> None:
    """超大媒体应复用file_id发送到PikPak且不再创建下载任务。"""
    async def scenario() -> RecordingConnector:
        connector = RecordingConnector()
        object.__setattr__(connector.config, "pikpak_threshold_bytes", 50)
        connector.business_connection_id = "business-7"
        await connector.receive({"update_id": 41, "message": {
            "message_id": 88, "date": 1_700_000_000, "chat": {"id": 42},
            "from": {"id": 99}, "video": {"file_id": "large-video", "file_size": 51}}})
        return connector

    connector = asyncio.run(scenario())
    assert connector.payload is None
    assert connector.api_calls == [
        ("sendVideo", {"business_connection_id": "business-7", "chat_id": "@pikpak_bot",
                       "video": "large-video"}),
        ("sendMessage", {"chat_id": "42",
                         "text": "Large media was forwarded to PikPak and will continue automatically.",
                         "reply_parameters": {"message_id": 88,
                                              "allow_sending_without_reply": True}})]


def test_poll_ack_after_durable_store_and_resets_offset_on_idle() -> None:
    """收据写入失败时保留游标，空响应后允许较小的新更新 ID。"""
    async def scenario() -> None:
        connector = RecordingConnector()
        connector.updates = [
            [{"update_id": 50}], [{"update_id": 50}], [], [{"update_id": 3}]]
        with patch.object(connector.inbox, "store", side_effect=OSError):
            try:
                await connector.poll_once()
            except OSError:
                pass
            else:
                raise AssertionError("expected inbox write to fail")
        assert connector.offset == 0
        await connector.poll_once()
        assert connector.offset == 51
        await connector.poll_once()
        assert connector.offset == 0
        await connector.poll_once()
        assert connector.offset == 4
        connector.inbox.close()

    asyncio.run(scenario())


def test_album_survives_restart_and_retries_without_duplicates(tmp_path) -> None:
    """跨批次和重启保存相册，下游瞬时失败后整组重试。"""
    path = str(tmp_path / "private" / "inbox.sqlite3")

    def photo(update_id: int) -> dict:
        return {"update_id": update_id, "message": {
            "message_id": update_id, "chat": {"id": 42}, "media_group_id": "album",
            "photo": [{"file_id": f"photo-{update_id}", "file_size": 20}]}}

    async def scenario() -> None:
        with patch("mytools_telegram_connector.inbox.time.time", return_value=1_700_000_000) as now:
            original = RecordingConnector(path)
            await original.receive(photo(61))
            assert original.offset == 62
            assert original.payloads == []
            original.inbox.close()

            restored = RecordingConnector(path)
            assert restored.offset == 0
            restored.updates = [[photo(61), photo(62)]]
            await restored.poll_once()
            assert restored.offset == 63
            now.return_value += 3
            restored.failures.append(ProviderError("Messaging", 503))
            assert await restored.deliver_once()
            assert restored.inbox.health()[0] == 1
            assert not await restored.deliver_once()
            now.return_value += 1
            assert await restored.deliver_once()
            assert not await restored.deliver_once()
            assert len(restored.payloads) == 1
            assert restored.payloads[0]["externalMessageId"] == "telegram_main:42:61:album:album"
            assert [part["providerFileId"] for part in restored.payloads[0]["parts"]] == ["photo-61", "photo-62"]
            restored.inbox.close()

    asyncio.run(scenario())


def test_poison_record_does_not_block_other_messages() -> None:
    """无效消息保留在收件箱供排查，其他消息继续投递。"""
    async def scenario() -> None:
        connector = RecordingConnector()
        connector.failures.append(ProviderError("Messaging", 400))
        await connector.receive({"update_id": 70, "message": {
            "message_id": 70, "chat": {"id": 42}, "text": "bad"}})
        await connector.receive({"update_id": 71, "message": {
            "message_id": 71, "chat": {"id": 42}, "text": "good"}})
        assert connector.inbox.health() == (0, 0, 1)
        assert connector.payloads[0]["body"] == "good"
        assert connector.health()["status"] == "DOWN"
        connector.inbox.close()

    asyncio.run(scenario())
