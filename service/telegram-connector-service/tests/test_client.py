"""Telegram Connector 单元测试。"""
from __future__ import annotations

import asyncio

from mytools_telegram_connector.client import TelegramConnector
from mytools_telegram_connector.config import Config


class RecordingConnector(TelegramConnector):
    """记录标准化消息且不访问网络。"""

    def __init__(self) -> None:
        config = Config("telegram_main", "secret", 7, frozenset({"42"}),
                        "http://telegram.test", None, "http://messaging.test", "message-token",
                        "internal-token", 45, 1024 * 1024, "@pikpak_bot", 20 * 1024 * 1024, "")
        super().__init__(config, object())  # type: ignore[arg-type]
        self.payload = None
        self.payloads = []
        self.api_calls = []

    async def _internal_json(self, url: str, payload: dict) -> dict:
        self.payload = payload
        self.payloads.append(payload)
        return {"id": "message-id"}

    async def api(self, method: str, payload: dict | None = None):
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
        await connector.flush_albums(True)
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
