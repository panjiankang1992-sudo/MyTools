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
                        "internal-token", 45, 1024 * 1024)
        super().__init__(config, object())  # type: ignore[arg-type]
        self.payload = None

    async def _internal_json(self, url: str, payload: dict) -> dict:
        self.payload = payload
        return {"id": "message-id"}


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
