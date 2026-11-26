"""Telegram Bot API 与内部消息服务客户端。"""
from __future__ import annotations

import asyncio
from datetime import UTC, datetime
import hashlib
import logging
import time
from typing import Any, AsyncIterator

import aiohttp

from .config import Config
from .inbox import Delivery, Inbox

logger = logging.getLogger(__name__)
MESSAGE_KINDS = ("message", "channel_post", "edited_message", "edited_channel_post")
DELIVERY_STALE_SECONDS = 60
OFFSET_MAXIMUM_AGE_SECONDS = 24 * 60 * 60


class ProviderError(RuntimeError):
    """保存可安全诊断的上游 HTTP 状态。"""

    def __init__(self, provider: str, status: int) -> None:
        super().__init__(f"{provider} HTTP {status}")
        self.status = status


class TelegramConnector:
    """长轮询 Telegram，并标准化入站消息。"""

    def __init__(self, config: Config, session: aiohttp.ClientSession, inbox: Inbox | None = None) -> None:
        self.config = config
        self.session = session
        self.offset = 0
        if inbox is None:
            # Telegram token 的冒号前为 Bot ID，轮换 token 后继续使用同一个收件箱。
            identity = f"{config.account_key}:{config.owner_id}:{config.bot_token.partition(':')[0]}"
            inbox = Inbox(config.inbox_path, hashlib.sha256(identity.encode()).hexdigest())
        self.inbox = inbox
        self.business_connection_id = inbox.business_connection(config.business_connection_id)
        self.last_poll_success: float | None = None
        self.last_poll_error: str | None = None
        self.last_delivery_tick: float | None = None
        self.delivery_ready = asyncio.Event()

    async def api(self, method: str, payload: dict[str, Any] | None = None) -> Any:
        """调用 Bot API，异常中不包含 token 或消息正文。"""
        url = f"{self.config.api_base_url}/bot{self.config.bot_token}/{method}"
        timeout = self.config.poll_timeout + 15 if method == "getUpdates" else 30
        async with self.session.post(url, json=payload or {}, proxy=self.config.proxy_url,
                                     timeout=aiohttp.ClientTimeout(total=timeout, connect=10)) as response:
            if response.status >= 400:
                raise ProviderError(f"Telegram {method}", response.status)
            body = await response.json(content_type=None)
            if not isinstance(body, dict) or not body.get("ok"):
                raise ProviderError(f"Telegram {method}", response.status)
            return body.get("result")

    async def poll_once(self) -> None:
        """拉取并依次处理一批更新。"""
        if (self.last_poll_success is not None
                and time.monotonic() - self.last_poll_success >= OFFSET_MAXIMUM_AGE_SECONDS):
            self.offset = 0
        # 初次立即确认 Bot API 可用，后续请求使用长轮询。
        timeout = self.config.poll_timeout if self.last_poll_success is not None else 0
        updates = await self.api("getUpdates", {
            "offset": self.offset, "timeout": timeout,
            "allowed_updates": [*MESSAGE_KINDS, "business_connection"]})
        if not isinstance(updates, list):
            raise RuntimeError("Telegram getUpdates returned an invalid result")
        receipts = []
        for update in updates:
            if (not isinstance(update, dict) or type(update.get("update_id")) is not int
                    or not 0 < update["update_id"] < 2**63 - 1):
                continue
            receipts.append((update["update_id"], self._accepted_update(update)))
        if receipts:
            # 先提交收据与原始消息，再在下一次 getUpdates 确认该批更新。
            self.inbox.store(receipts)
            self.offset = max(update_id for update_id, _ in receipts) + 1
            self.delivery_ready.set()
        else:
            # 空响应表示前一批已确认。空闲一周后 Telegram 可能返回更小的新 ID。
            self.offset = 0
        self.last_poll_success = time.monotonic()
        self.last_poll_error = None

    def _accepted_update(self, update: dict[str, Any]) -> tuple[str, str, str, dict[str, Any]] | None:
        """分类并过滤本连接器处理的更新。"""
        business_connection = update.get("business_connection")
        if isinstance(business_connection, dict):
            return "business_connection", "", "", business_connection
        kind, message = next(((name, update[name]) for name in
                              MESSAGE_KINDS
                              if isinstance(update.get(name), dict)), ("", {}))
        chat = message.get("chat") if isinstance(message.get("chat"), dict) else {}
        chat_id = str(chat.get("id") or "")
        message_id = str(message.get("message_id") or "")
        if not kind or not self._allowed(chat_id) or not message_id.isascii() or not message_id.isdecimal():
            return None
        album_id = str(message.get("media_group_id") or "").strip()
        return kind, chat_id, album_id, message

    async def _deliver(self, delivery: Delivery) -> None:
        """标准化一条消息或已聚合的 Telegram 相册。"""
        messages = sorted(delivery.messages, key=lambda item: int(item.get("message_id") or 0))
        if delivery.kind == "business_connection":
            self._capture_business_connection(messages[0])
            return
        album_id = delivery.album
        message = messages[0]
        chat = message.get("chat") if isinstance(message.get("chat"), dict) else {}
        sender_data = message.get("from") if isinstance(message.get("from"), dict) else {}
        chat_id = str(chat.get("id") or "")
        message_id = str(message.get("message_id") or "")
        sender = str(sender_data.get("id") or chat_id)
        oversized = [(album_message, source_key, item) for album_message in messages
                     for source_key, _, item in self._attachment_candidates(album_message)
                     if int(item.get("file_size") or 0) > self.config.pikpak_threshold_bytes]
        if oversized and self.business_connection_id:
            # 超大文件直接复用Telegram file_id以用户身份发送，避免云Bot API下载上限。
            self.inbox.relay_started(delivery.id)
            for album_message, source_key, item in oversized:
                await self._relay_to_pikpak(source_key, item, album_message)
            await self.send_text(chat_id, int(message_id),
                                 "Large media was forwarded to PikPak and will continue automatically.")
            return
        parts: list[dict[str, Any]] = []
        body_values = []
        seen_messages = set()
        for album_message in messages:
            for item in self._message_chain(album_message):
                identity = str(item.get("message_id") or id(item))
                if identity in seen_messages:
                    continue
                seen_messages.add(identity)
                text = str(item.get("text") or item.get("caption") or "").strip()
                links = self._entity_links(item)
                if text and text not in body_values:
                    parts.append(self._text_part(text))
                    body_values.append(text)
                for link in links:
                    if link not in text and link not in body_values:
                        parts.append(self._text_part(link))
                        body_values.append(link)
                parts.extend(self._attachment_parts(item))
        parts = parts[:20]
        body = "\n".join(value for value in body_values if value) or "[attachment]"
        dates = [int(value.get("date") or 0) for value in messages if value.get("date")]
        received_at = datetime.fromtimestamp(min(dates), UTC) if dates else datetime.now(UTC)
        # 相册仍以album_id保证聚合幂等，但必须保留首条真实message_id供原会话回复。
        external = f"{message_id}:album:{album_id}" if album_id else message_id
        payload = {
            "ownerId": self.config.owner_id, "channelType": "TELEGRAM",
            "externalMessageId": f"{self.config.account_key}:{chat_id}:{external}",
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
        candidates = self._attachment_candidates(message)
        result = []
        message_id = str(message.get("message_id") or "unknown")
        for index, (_, attachment_type, item) in enumerate(candidates[:20]):
            file_id = str(item.get("file_id") or "")
            if not file_id:
                continue
            result.append({"type": "ATTACHMENT", "text": None,
                "attachmentType": attachment_type, "providerFileId": file_id[:512],
                "providerAccountKey": self.config.account_key, "sourceUrl": None,
                # 相册内每条消息都会从index=0开始，默认文件名必须包含message_id避免同目录冲突。
                "fileName": str(item.get("file_name") or
                                f"telegram-{attachment_type.lower()}-{message_id}-{index + 1}")[:1024],
                "mimeType": str(item.get("mime_type") or self._default_mime_type(attachment_type))[:255],
                "declaredSize": int(item["file_size"]) if int(item.get("file_size") or 0) > 0 else None})
        return result

    @staticmethod
    def _attachment_candidates(message: dict[str, Any]) -> list[tuple[str, str, dict[str, Any]]]:
        """返回消息中的原生媒体字段、标准类型与文件对象。"""
        candidates: list[tuple[str, str, dict[str, Any]]] = []
        photos = message.get("photo") if isinstance(message.get("photo"), list) else []
        if photos:
            photos = [item for item in photos if isinstance(item, dict)]
            if photos:
                candidates.append(("photo", "IMAGE",
                                   max(photos, key=lambda item: int(item.get("file_size") or 0))))
        mapping = (("video", "VIDEO"), ("animation", "VIDEO"), ("audio", "RECORD"),
                   ("voice", "RECORD"), ("video_note", "VIDEO"),
                   ("document", "FILE"), ("sticker", "FILE"))
        for key, attachment_type in mapping:
            if isinstance(message.get(key), dict):
                item = message[key]
                if key == "sticker" and (item.get("is_video") or item.get("is_animated")):
                    attachment_type = "VIDEO"
                candidates.append((key, attachment_type, item))
        return candidates

    def _capture_business_connection(self, value: dict[str, Any]) -> None:
        """捕获用户授权的Business连接，供超大文件中继使用。"""
        connection_id = str(value.get("id") or "").strip()
        rights = value.get("rights") if isinstance(value.get("rights"), dict) else {}
        if value.get("is_enabled") and rights.get("can_reply") and connection_id:
            self.inbox.save_business_connection(connection_id)
            self.business_connection_id = connection_id
            logger.info("Telegram Business relay connection is ready")
        elif connection_id and connection_id == self.business_connection_id:
            self.inbox.save_business_connection("")
            self.business_connection_id = ""
            logger.warning("Telegram Business relay connection was disabled")

    async def _relay_to_pikpak(self, source_key: str, item: dict[str, Any],
                               message: dict[str, Any]) -> None:
        """使用Business身份把一个Telegram原生文件发送给PikPak Bot。"""
        methods = {"photo": ("sendPhoto", "photo"), "video": ("sendVideo", "video"),
                   "animation": ("sendAnimation", "animation"),
                   "audio": ("sendAudio", "audio"), "voice": ("sendVoice", "voice"),
                   "video_note": ("sendVideoNote", "video_note"),
                   "document": ("sendDocument", "document"),
                   "sticker": ("sendSticker", "sticker")}
        method, parameter = methods[source_key]
        payload = {"business_connection_id": self.business_connection_id,
                   "chat_id": self.config.pikpak_bot,
                   parameter: str(item.get("file_id") or "")}
        caption = str(message.get("caption") or "").strip()
        if caption and source_key not in {"video_note", "sticker"}:
            payload["caption"] = caption[:1024]
        await self.api(method, payload)

    def business_status(self) -> dict[str, Any]:
        """返回受鉴权内部接口使用的Business连接状态。"""
        return {"connected": bool(self.business_connection_id),
                "connectionId": self.business_connection_id or None}

    @staticmethod
    def _default_mime_type(attachment_type: str) -> str:
        """为Telegram未返回MIME的原生媒体提供稳定类型。"""
        return {"IMAGE": "image/jpeg", "VIDEO": "video/mp4", "RECORD": "audio/ogg"}.get(
            attachment_type, "application/octet-stream")

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
        response = await self.session.get(url, proxy=self.config.proxy_url,
                                          timeout=aiohttp.ClientTimeout(connect=15, sock_read=60))
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
        async with self.session.post(url, headers=headers, json=payload,
                                     timeout=aiohttp.ClientTimeout(total=15, connect=5)) as response:
            if response.status >= 400:
                raise ProviderError("Messaging", response.status)
            body = await response.json(content_type=None)
            if not isinstance(body, dict):
                raise ProviderError("Messaging", response.status)
            return body

    @staticmethod
    def _error_code(exception: Exception) -> str:
        """去掉可能包含请求地址、凭证和正文的异常文本。"""
        if isinstance(exception, ProviderError):
            return f"HTTP_{exception.status}"
        return type(exception).__name__

    async def deliver_once(self) -> bool:
        """尝试处理最早可投递记录，瞬时故障退避后重试。"""
        delivery = self.inbox.claim()
        if delivery is None:
            return False
        try:
            await self._deliver(delivery)
        except Exception as exception:
            code = self._error_code(exception)
            permanent = isinstance(exception, (TypeError, ValueError, OverflowError)) or (
                isinstance(exception, ProviderError) and exception.status in (400, 413, 422))
            self.inbox.fail(delivery, code, permanent)
            logger.warning("Telegram inbound delivery failed: %s", code)
        else:
            self.inbox.complete(delivery.id)
        return True

    async def deliver_pending(self, stop: asyncio.Event) -> None:
        """独立投递本地消息，避免长轮询和下游重试互相阻塞。"""
        while not stop.is_set():
            self.last_delivery_tick = time.monotonic()
            self.delivery_ready.clear()
            if await self.deliver_once():
                continue
            try:
                await asyncio.wait_for(self.delivery_ready.wait(), timeout=0.5)
            except TimeoutError:
                pass

    def health(self) -> dict[str, Any]:
        """返回长轮询与本地待投递记录的就绪状态。"""
        now = time.monotonic()
        poll_ready = (self.last_poll_success is not None
                      and now - self.last_poll_success <= self.config.poll_timeout + 45)
        worker_ready = (self.last_delivery_tick is not None
                        and now - self.last_delivery_tick <= DELIVERY_STALE_SECONDS)
        pending, age, dead = self.inbox.health()
        delivery_ready = worker_ready and age < DELIVERY_STALE_SECONDS and dead == 0
        return {"status": "UP" if poll_ready and delivery_ready else "DOWN",
                "pollReady": poll_ready, "pollLastError": self.last_poll_error,
                "deliveryReady": delivery_ready, "pendingInbound": pending,
                "oldestPendingAgeSeconds": age, "deadInbound": dead}

    async def run(self, stop: asyncio.Event) -> None:
        """持续轮询并在瞬时错误后退避。"""
        backoff = 1.0
        while not stop.is_set():
            try:
                await self.poll_once()
                backoff = 1.0
            except Exception as exception:
                self.last_poll_error = self._error_code(exception)
                logger.warning("Telegram polling failed: %s", self.last_poll_error)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 60.0)
