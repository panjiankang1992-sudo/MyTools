"""Telegram Bot API 与内部消息服务客户端。"""
from __future__ import annotations

import asyncio
from datetime import UTC, datetime
import logging
from typing import Any, AsyncIterator

import aiohttp

from .config import Config

logger = logging.getLogger(__name__)


class TelegramConnector:
    """长轮询 Telegram，并标准化入站消息。"""

    def __init__(self, config: Config, session: aiohttp.ClientSession) -> None:
        self.config = config
        self.session = session
        self.offset = 0

    async def api(self, method: str, payload: dict[str, Any] | None = None) -> Any:
        """调用 Bot API，异常中不包含 token 或消息正文。"""
        url = f"{self.config.api_base_url}/bot{self.config.bot_token}/{method}"
        async with self.session.post(url, json=payload or {}, proxy=self.config.proxy_url) as response:
            body = await response.json(content_type=None)
            if response.status >= 400 or not isinstance(body, dict) or not body.get("ok"):
                raise RuntimeError(f"Telegram API {method} failed with HTTP {response.status}")
            return body.get("result")

    async def poll_once(self) -> None:
        """拉取并依次处理一批更新。"""
        updates = await self.api("getUpdates", {
            "offset": self.offset, "timeout": self.config.poll_timeout,
            "allowed_updates": ["message", "channel_post", "edited_message",
                                "edited_channel_post"]})
        if not isinstance(updates, list):
            raise RuntimeError("Telegram getUpdates returned an invalid result")
        for update in updates:
            if not isinstance(update, dict) or not isinstance(update.get("update_id"), int):
                continue
            try:
                await self.receive(update)
            finally:
                # 无效或未授权消息也必须前移，内部写入由 externalMessageId 保证重放幂等。
                self.offset = max(self.offset, int(update["update_id"]) + 1)

    async def receive(self, update: dict[str, Any]) -> None:
        """标准化一个 Telegram 更新并写入 Messaging。"""
        kind, message = next(((name, update[name]) for name in
                              ("message", "channel_post", "edited_message", "edited_channel_post")
                              if isinstance(update.get(name), dict)), ("", {}))
        chat = message.get("chat") if isinstance(message.get("chat"), dict) else {}
        sender_data = message.get("from") if isinstance(message.get("from"), dict) else {}
        chat_id = str(chat.get("id") or "")
        message_id = str(message.get("message_id") or "")
        if not kind or not self._allowed(chat_id) or not message_id:
            return
        sender = str(sender_data.get("id") or chat_id)
        chain = self._message_chain(message)
        parts: list[dict[str, Any]] = []
        body_values = []
        for item in chain:
            text = str(item.get("text") or item.get("caption") or "").strip()
            links = self._entity_links(item)
            if text:
                parts.append(self._text_part(text))
                body_values.append(text)
            for link in links:
                if link not in text and link not in body_values:
                    parts.append(self._text_part(link))
                    body_values.append(link)
            parts.extend(self._attachment_parts(item))
        body = "\n".join(value for value in body_values if value) or "[attachment]"
        received_at = datetime.fromtimestamp(int(message.get("date") or 0), UTC) \
            if message.get("date") else datetime.now(UTC)
        payload = {
            "ownerId": self.config.owner_id, "channelType": "TELEGRAM",
            "externalMessageId": f"{self.config.account_key}:{chat_id}:{message_id}",
            "conversationKey": f"{self.config.account_key}:{chat_id}", "sender": sender,
            "subject": None, "body": body, "receivedAt": received_at.isoformat(), "parts": parts}
        await self._internal_json(self.config.messaging_url + "/internal/v1/inbound-messages", payload)

    @staticmethod
    def _message_chain(message: dict[str, Any]) -> list[dict[str, Any]]:
        """按被回复消息到当前消息的顺序展开有界引用链。"""
        values = []
        current = message
        seen = set()
        for _ in range(5):
            message_id = str(current.get("message_id") or "")
            if message_id and message_id in seen:
                break
            if message_id:
                seen.add(message_id)
            values.append(current)
            reply = current.get("reply_to_message")
            if not isinstance(reply, dict):
                break
            current = reply
        return list(reversed(values))

    @staticmethod
    def _text_part(text: str) -> dict[str, Any]:
        """创建标准文本分段。"""
        return {"type": "TEXT", "text": text, "attachmentType": None,
                "providerFileId": None, "providerAccountKey": None, "sourceUrl": None,
                "fileName": None, "mimeType": None, "declaredSize": None}

    @staticmethod
    def _entity_links(message: dict[str, Any]) -> list[str]:
        """提取 Telegram 隐藏链接实体。"""
        result: list[str] = []
        for key in ("entities", "caption_entities"):
            values = message.get(key) if isinstance(message.get(key), list) else []
            for entity in values:
                if isinstance(entity, dict) and entity.get("type") == "text_link":
                    url = str(entity.get("url") or "")
                    if url.startswith(("http://", "https://")) and url not in result:
                        result.append(url)
        preview = message.get("link_preview_options")
        if isinstance(preview, dict):
            url = str(preview.get("url") or "")
            if url.startswith(("http://", "https://")) and url not in result:
                result.append(url)
        return result

    def _attachment_parts(self, message: dict[str, Any]) -> list[dict[str, Any]]:
        """把 Telegram 原生媒体映射为附件分段。"""
        candidates: list[tuple[str, dict[str, Any]]] = []
        photos = message.get("photo") if isinstance(message.get("photo"), list) else []
        if photos:
            photos = [item for item in photos if isinstance(item, dict)]
            if photos:
                candidates.append(("IMAGE", max(photos, key=lambda item: int(item.get("file_size") or 0))))
        mapping = (("video", "VIDEO"), ("animation", "VIDEO"), ("audio", "RECORD"),
                   ("voice", "RECORD"), ("video_note", "VIDEO"),
                   ("document", "FILE"), ("sticker", "FILE"))
        for key, attachment_type in mapping:
            if isinstance(message.get(key), dict):
                item = message[key]
                if key == "sticker" and (item.get("is_video") or item.get("is_animated")):
                    attachment_type = "VIDEO"
                candidates.append((attachment_type, item))
        result = []
        for index, (attachment_type, item) in enumerate(candidates[:20]):
            file_id = str(item.get("file_id") or "")
            if not file_id:
                continue
            result.append({"type": "ATTACHMENT", "text": None,
                "attachmentType": attachment_type, "providerFileId": file_id[:512],
                "providerAccountKey": self.config.account_key, "sourceUrl": None,
                "fileName": str(item.get("file_name") or f"telegram-{attachment_type.lower()}-{index + 1}")[:1024],
                "mimeType": str(item.get("mime_type") or "application/octet-stream")[:255],
                "declaredSize": int(item["file_size"]) if int(item.get("file_size") or 0) > 0 else None})
        return result

    async def send_text(self, chat_id: str, message_id: int, text: str) -> None:
        """回复原 Telegram 会话。"""
        if not self._allowed(chat_id) or not text or len(text) > 4096:
            raise ValueError("Telegram reply is invalid")
        await self.api("sendMessage", {"chat_id": chat_id, "text": text,
                                       "reply_parameters": {"message_id": message_id,
                                                            "allow_sending_without_reply": True}})

    async def file_stream(self, file_id: str) -> tuple[dict[str, str], AsyncIterator[bytes]]:
        """解析并流式读取 provider 文件，返回安全响应元数据。"""
        result = await self.api("getFile", {"file_id": file_id})
        if not isinstance(result, dict) or not result.get("file_path"):
            raise RuntimeError("Telegram getFile returned no file path")
        size = int(result.get("file_size") or 0)
        if size > self.config.maximum_file_bytes:
            raise ValueError("Telegram file exceeds configured limit")
        url = f"{self.config.api_base_url}/file/bot{self.config.bot_token}/{result['file_path']}"
        response = await self.session.get(url, proxy=self.config.proxy_url)
        if response.status >= 400:
            response.release()
            raise RuntimeError(f"Telegram file request failed with HTTP {response.status}")
        headers = {"Content-Type": response.headers.get("Content-Type", "application/octet-stream")}
        if response.content_length is not None:
            headers["Content-Length"] = str(response.content_length)

        async def chunks() -> AsyncIterator[bytes]:
            try:
                async for chunk in response.content.iter_chunked(1024 * 1024):
                    yield chunk
            finally:
                response.release()
        return headers, chunks()

    def _allowed(self, chat_id: str) -> bool:
        """空白名单沿用旧服务语义，允许所有 Telegram 会话。"""
        return bool(chat_id) and (not self.config.allowed_chat_ids
                                  or chat_id in self.config.allowed_chat_ids)

    async def _internal_json(self, url: str, payload: dict[str, Any]) -> dict[str, Any]:
        headers = {"Authorization": f"Bearer {self.config.messaging_token}"}
        async with self.session.post(url, headers=headers, json=payload) as response:
            body = await response.json(content_type=None)
            if response.status >= 400 or not isinstance(body, dict):
                raise RuntimeError(f"Messaging request failed with HTTP {response.status}")
            return body

    async def run(self, stop: asyncio.Event) -> None:
        """持续轮询并在瞬时错误后退避。"""
        backoff = 1.0
        while not stop.is_set():
            try:
                await self.poll_once()
                backoff = 1.0
            except asyncio.CancelledError:
                raise
            except Exception as exception:
                logger.warning("Telegram polling failed: %s", exception)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 60.0)
