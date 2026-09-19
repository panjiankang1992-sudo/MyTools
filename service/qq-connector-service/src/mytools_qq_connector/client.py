"""官方 QQ、Messaging、Scheduler 与 OneBot 原子客户端。"""
from __future__ import annotations

import asyncio
import base64
from datetime import UTC, datetime
import hashlib
import json
import logging
import mimetypes
import random
import re
import time
from typing import Any

import aiohttp

from .config import Config
from .inbound_wal import (
    InboundEventWal,
    InboundWalEntry,
    InboundWalError,
)
from .login_wal import (
    LoginCommand,
    LoginCommandWal,
    LoginWalConflictError,
    LoginWalEntry,
)

logger = logging.getLogger(__name__)
COMPOSITE_ATTACHMENT = re.compile(
    r"\[附件\d+\]\s+类型:(?P<type>\S+)\s+文件名:(?P<filename>.*?)\s+"
    r"(?:尺寸:\S+\s+)?大小:(?P<size>\S+)\s+URL:(?P<url>https?://\S+)", re.MULTILINE)
MAXIMUM_NESTED_VALUES = 100_000
SUPPORTED_MESSAGE_EVENTS = {"C2C_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE",
                            "AT_MESSAGE_CREATE", "DIRECT_MESSAGE_CREATE"}
QQ_HTTP_TIMEOUT = aiohttp.ClientTimeout(total=30, connect=10, sock_read=20)
INTERNAL_HTTP_TIMEOUT = aiohttp.ClientTimeout(total=15, connect=5, sock_read=10)
LOGIN_ACKNOWLEDGEMENT_TIMEOUT_SECONDS = 3.0
LOGIN_WAL_BATCH_SIZE = 1
LOGIN_PRIVATE_CHAT_GUIDANCE = (
    "\u767b\u5f55\u547d\u4ee4\u4ec5\u652f\u6301\u79c1\u804a\uff0c"
    "\u8bf7\u79c1\u804a\u673a\u5668\u4eba\u53d1\u9001\u201c\u767b\u5f55\u201d\u3002"
)
PERMANENT_INBOUND_HTTP_STATUSES = {400, 413, 422}
INBOUND_WAL_LEASE_SECONDS = 60.0


class LoginWalUnavailableError(RuntimeError):
    """表示登录命令无法写入或读取稳定存储。"""


class InboundWalUnavailableError(RuntimeError):
    """表示原始入站事件无法写入或读取稳定存储。"""


class PermanentLoginCommandError(RuntimeError):
    """表示重试不会恢复的登录命令错误。"""


class InternalHttpError(RuntimeError):
    """表示内部 HTTP 调用返回了可分类状态码。"""

    def __init__(self, status: int) -> None:
        super().__init__(f"internal request failed with HTTP {status}")
        self.status = status
        self.error_code = f"HTTP_{status}"
        self.retryable = status in {408, 425, 429} or status >= 500
        # 仅确认由单条入站载荷决定的状态才可推进游标；鉴权、路由和冲突错误必须保留重放。
        self.permanent_input = status in PERMANENT_INBOUND_HTTP_STATUSES


class PermanentInboundEventError(RuntimeError):
    """表示应持久隔离并允许 Gateway 前进的输入错误。"""

    def __init__(self, error_code: str) -> None:
        super().__init__(error_code)
        self.error_code = error_code


class QQConnector:
    """维护官方 QQ Gateway，并把消息转交给内部服务。"""

    def __init__(self, config: Config, session: aiohttp.ClientSession,
                 login_wal: LoginCommandWal, inbound_wal: InboundEventWal) -> None:
        self.config = config
        self.session = session
        self.login_wal = login_wal
        self.inbound_wal = inbound_wal
        self._token = ""
        self._token_expires_at = 0.0
        self._token_lock = asyncio.Lock()
        checkpoint = self.inbound_wal.load_checkpoint()
        if checkpoint is not None and checkpoint.account_key not in {None, config.account_key}:
            raise InboundWalUnavailableError("QQ Gateway checkpoint account does not match")
        self._session_id = "" if checkpoint is None else checkpoint.session_id
        self._sequence: int | None = None if checkpoint is None else checkpoint.sequence
        self._gateway_ready = False
        self._gateway_last_activity = 0.0
        self._gateway_error_code: str | None = None
        self._login_worker_running = False
        self._login_worker_active = False
        self._login_worker_last_tick = 0.0
        self._login_worker_error_code: str | None = None
        self._login_wakeup = asyncio.Event()
        self._inbound_worker_running = False
        self._inbound_worker_active = False
        self._inbound_worker_last_tick = 0.0
        self._inbound_worker_error_code: str | None = None
        self._inbound_wakeup = asyncio.Event()

    async def access_token(self) -> str:
        """按服务端有效期缓存官方 QQ 访问令牌。"""
        if self._token and time.monotonic() < self._token_expires_at - 60:
            return self._token
        # 并发回复共享一次刷新，避免令牌失效时同时放大到鉴权端点。
        async with self._token_lock:
            if self._token and time.monotonic() < self._token_expires_at - 60:
                return self._token
            async with self.session.post(self.config.token_url, json={
                    "appId": self.config.app_id, "clientSecret": self.config.app_secret},
                    timeout=QQ_HTTP_TIMEOUT) as response:
                body = await response.json(content_type=None)
                if response.status >= 400 or not body.get("access_token"):
                    raise RuntimeError(f"QQ token request failed with HTTP {response.status}")
            self._token = str(body["access_token"])
            self._token_expires_at = time.monotonic() + int(body.get("expires_in", 7200))
            return self._token

    def _invalidate_access_token(self, rejected_token: str) -> None:
        """仅失效被服务端明确拒绝且仍为当前值的访问令牌。"""
        if self._token == rejected_token:
            self._token = ""
            self._token_expires_at = 0.0

    async def gateway_url(self) -> str:
        """获取官方 Gateway 地址。"""
        if self.config.gateway_url:
            return self.config.gateway_url
        body = await self._qq_request("GET", "/gateway/bot")
        if not body.get("url"):
            raise RuntimeError("QQ gateway response has no URL")
        return str(body["url"])

    async def receive(self, payload: dict[str, Any]) -> None:
        """持久化入站消息，并任务化处理授权登录命令。"""
        event_type = str(payload.get("t") or "")
        data = payload.get("d") if isinstance(payload.get("d"), dict) else {}
        author = data.get("author") if isinstance(data.get("author"), dict) else {}
        sender = str(author.get("user_openid") or author.get("member_openid") or author.get("id") or "")
        message_id = str(data.get("id") or "")
        content = str(data.get("content") or "").strip()
        target, conversation_type = self._conversation(data, event_type, sender)
        if event_type not in SUPPORTED_MESSAGE_EVENTS:
            return
        if not sender or not message_id or not target:
            raise PermanentInboundEventError("INVALID_MESSAGE_IDENTITY")
        is_login_text = content in {"登录", "登陆"}
        if is_login_text and event_type != "C2C_MESSAGE_CREATE":
            # 群聊和频道不得接收登录凭据，但必须明确引导到受控私聊入口。
            try:
                await asyncio.wait_for(
                    self.send_text(target, message_id, LOGIN_PRIVATE_CHAT_GUIDANCE, 1,
                                   event_type=event_type),
                    timeout=LOGIN_ACKNOWLEDGEMENT_TIMEOUT_SECONDS,
                )
            except asyncio.CancelledError:
                raise
            except Exception as exception:
                logger.warning("QQ login guidance failed errorCode=%s",
                               type(exception).__name__)
            return
        is_login_command = event_type == "C2C_MESSAGE_CREATE" \
            and sender == self.config.allowed_sender and is_login_text
        if is_login_command:
            # 登录属于连接器控制命令，不得进入通用消息自动化并产生 NO_MATCH 下载回执。
            try:
                command = LoginCommand.create(
                    self.config.account_key, message_id, sender,
                    self.config.login_deadline_seconds)
                pending = await asyncio.to_thread(self.login_wal.store, command)
                self._login_worker_error_code = None
            except (LoginWalConflictError, ValueError) as exception:
                # 确定性坏命令不得卡住 Gateway 游标或覆盖同键记录。
                logger.error("QQ login command rejected errorCode=%s", type(exception).__name__)
                return
            except Exception as exception:
                self._login_worker_error_code = type(exception).__name__[:128]
                raise LoginWalUnavailableError(
                    "QQ login command persistence failed") from exception
            if not pending:
                return
            self._login_wakeup.set()
            try:
                await asyncio.wait_for(
                    self.send_text(sender, message_id, "正在生成登录二维码，请稍候。", 1),
                    timeout=LOGIN_ACKNOWLEDGEMENT_TIMEOUT_SECONDS)
            except asyncio.CancelledError:
                raise
            except Exception as exception:
                # 即时提示失败不能阻断二维码生成。
                logger.warning("QQ login acknowledgement failed errorCode=%s",
                               type(exception).__name__)
            return
        # 开始回执由统一 Message Automation 发送，连接器只负责标准化和入库。
        try:
            await self._messaging_receive(payload, sender, message_id, content,
                                          target, conversation_type)
        except InternalHttpError as exception:
            if exception.permanent_input:
                raise PermanentInboundEventError(exception.error_code) from exception
            raise
        except (RecursionError, ValueError) as exception:
            raise PermanentInboundEventError(
                "INVALID_MESSAGE_STRUCTURE") from exception

    async def _messaging_receive(self, payload: dict[str, Any], sender: str,
                                 message_id: str, content: str, target: str | None = None,
                                 conversation_type: str = "c2c") -> str:
        parts = [{"type": "TEXT", "text": content or "[empty]", "attachmentType": None,
                  "providerFileId": None, "providerAccountKey": None, "sourceUrl": None,
                  "fileName": None, "mimeType": None, "declaredSize": None}]
        data = payload.get("d") if isinstance(payload.get("d"), dict) else {}
        attachment_urls = []
        attachments = self._recursive_attachments(data)
        for index, attachment in enumerate(attachments[:100]):
            source_url = str(attachment.get("voice_wav_url") or attachment.get("download_url")
                             or attachment.get("file_url") or attachment.get("url") or "").strip()
            if not source_url.startswith(("http://", "https://")):
                continue
            mime_type = str(attachment.get("content_type") or attachment.get("mime")
                            or "application/octet-stream")[:255]
            attachment_type = ("IMAGE" if mime_type.startswith("image/") else
                               "VIDEO" if mime_type.startswith("video/") else
                               "RECORD" if mime_type.startswith("audio/") else "FILE")
            raw_size = attachment.get("size") or attachment.get("file_size")
            try:
                declared_size = int(raw_size) if raw_size is not None else None
            except (TypeError, ValueError):
                declared_size = None
            if declared_size is not None and declared_size <= 0:
                declared_size = None
            attachment_urls.append(source_url)
            parts.append({"type": "ATTACHMENT", "text": None,
                          "attachmentType": attachment_type,
                          "providerFileId": str(attachment.get("file_id") or attachment.get("id") or "")[:512]
                              or None,
                          "providerAccountKey": self.config.account_key,
                          "sourceUrl": source_url[:4096],
                          "fileName": str(attachment.get("filename") or attachment.get("file_name")
                                          or attachment.get("name") or f"qq-attachment-{index + 1}")[:1024],
                          "mimeType": mime_type, "declaredSize": declared_size})
        normalized_content = content
        for source_url in attachment_urls:
            # 结构化附件已独立入库，正文不再重复暴露同一 URL 触发第二次下载。
            normalized_content = normalized_content.replace(source_url, "")
        normalized_body = normalized_content.strip() or "[empty]"
        event_type = str(payload.get("t") or "C2C_MESSAGE_CREATE")
        body = {"ownerId": self.config.owner_id, "channelType": "QQ",
                "externalMessageId": f"{self.config.account_key}:{event_type}:{message_id}",
                "conversationKey": f"{self.config.account_key}:{conversation_type}:{target or sender}", "sender": sender,
                "subject": None, "body": normalized_body,
                "receivedAt": datetime.now(UTC).isoformat(), "parts": parts}
        await self._internal_json("POST", self.config.messaging_url + "/internal/v1/inbound-messages",
                                  body, self.config.messaging_token)
        return normalized_body

    @staticmethod
    def _conversation(data: dict[str, Any], event_type: str, sender: str) -> tuple[str, str]:
        """解析不同官方 QQ 消息事件的回复目标和会话类型。"""
        if event_type == "C2C_MESSAGE_CREATE":
            return sender, "c2c"
        if event_type == "GROUP_AT_MESSAGE_CREATE":
            return str(data.get("group_openid") or ""), "group"
        if event_type in {"AT_MESSAGE_CREATE", "DIRECT_MESSAGE_CREATE"}:
            return str(data.get("channel_id") or ""), "channel"
        return "", "unknown"

    @staticmethod
    def _parse_size(value: object) -> int | None:
        """解析 QQ 复合消息中的有界文件大小。"""
        try:
            size = int(value)
            return size if size > 0 else None
        except (TypeError, ValueError):
            match = re.fullmatch(r"(\d+(?:\.\d+)?)\s*([KMGT]?B)", str(value), re.IGNORECASE)
            if match is None:
                return None
            units = {"B": 1, "KB": 1024, "MB": 1024 ** 2,
                     "GB": 1024 ** 3, "TB": 1024 ** 4}
            return round(float(match.group(1)) * units[match.group(2).upper()])

    @classmethod
    def _recursive_attachments(cls, value: Any) -> list[dict[str, Any]]:
        """迭代扫描 QQ 复合结构和内嵌 JSON，提取并去重全部附件。"""
        result = []
        seen_urls = set()
        seen_containers = set()
        stack: list[tuple[Any, str]] = [(value, "root")]
        visited = 0

        def append(item: dict[str, Any]) -> None:
            url = str(item.get("voice_wav_url") or item.get("download_url")
                      or item.get("file_url") or item.get("url") or "").strip()
            if url.startswith(("http://", "https://")) and url not in seen_urls:
                seen_urls.add(url)
                result.append(item)

        while stack:
            current, context = stack.pop()
            visited += 1
            if visited > MAXIMUM_NESTED_VALUES:
                raise ValueError("QQ message exceeds nested value limit")
            if isinstance(current, dict):
                identity = id(current)
                if identity in seen_containers:
                    continue
                seen_containers.add(identity)
                has_url = any(current.get(key) for key in
                              ("voice_wav_url", "download_url", "file_url", "url"))
                looks_like = context.lower() in {"attachment", "attachments", "media", "medias",
                                                  "file", "files"} \
                    or any(key in current for key in
                           ("filename", "file_name", "content_type", "mime", "file_id"))
                if has_url and looks_like:
                    append(current)
                for key, child in reversed(tuple(current.items())):
                    if isinstance(child, (dict, list)):
                        stack.append((child, str(key)))
                    elif isinstance(child, str):
                        for match in COMPOSITE_ATTACHMENT.finditer(child):
                            name = match.group("filename").strip()
                            append({"url": match.group("url"), "filename": name,
                                    "content_type": mimetypes.guess_type(name)[0]
                                    or "application/octet-stream",
                                    "size": cls._parse_size(match.group("size"))})
                        candidate = child.strip()
                        if len(candidate) <= 2 * 1024 * 1024 and candidate[:1] in {"{", "["}:
                            try:
                                decoded = json.loads(candidate)
                            except (json.JSONDecodeError, RecursionError):
                                continue
                            if isinstance(decoded, (dict, list)):
                                stack.append((decoded, str(key)))
            elif isinstance(current, list):
                identity = id(current)
                if identity in seen_containers:
                    continue
                seen_containers.add(identity)
                stack.extend((child, context) for child in reversed(current))
        return result

    async def run_inbound_worker(self, stop: asyncio.Event) -> None:
        """并行恢复持久化 Dispatch，失败按单事件预算退避且不阻塞后续消息。"""
        scan_failures = 0
        self._inbound_worker_running = True
        try:
            while not stop.is_set():
                self._inbound_worker_last_tick = time.monotonic()
                # 先清除旧信号；扫描期间到达的新信号必须保留给随后的等待。
                self._inbound_wakeup.clear()
                try:
                    entries = await asyncio.to_thread(
                        self.inbound_wal.claim_batch, time.time(),
                        self.config.inbound_worker_concurrency, INBOUND_WAL_LEASE_SECONDS,
                        self.config.inbound_max_attempts)
                    scan_failures = 0
                    self._inbound_worker_error_code = None
                    if entries:
                        self._inbound_worker_active = True
                        try:
                            results = await asyncio.gather(
                                *(self._process_inbound_entry(entry) for entry in entries))
                        finally:
                            self._inbound_worker_active = False
                        if all(results):
                            self._inbound_worker_error_code = None
                        continue
                except asyncio.CancelledError:
                    raise
                except Exception as exception:
                    scan_failures += 1
                    self._inbound_worker_error_code = type(exception).__name__[:128]
                    logger.error("QQ inbound WAL worker failed attempt=%s errorCode=%s",
                                 scan_failures, self._inbound_worker_error_code)
                    if scan_failures >= self.config.inbound_max_attempts:
                        raise InboundWalUnavailableError(
                            "QQ inbound WAL worker exhausted") from exception
                delay = min(self.config.inbound_retry_max_seconds,
                            2 ** max(0, scan_failures - 1)) \
                    if scan_failures else self.config.inbound_worker_poll_seconds
                try:
                    await asyncio.wait_for(self._inbound_wakeup.wait(), timeout=delay)
                except TimeoutError:
                    pass
        finally:
            self._inbound_worker_active = False
            self._inbound_worker_running = False

    async def _process_inbound_entry(self, entry: InboundWalEntry) -> bool:
        """处理单条已领取 Dispatch，并原子完成、隔离或安排有限重试。"""
        try:
            await self.receive(entry.payload)
        except asyncio.CancelledError:
            raise
        except PermanentInboundEventError as exception:
            try:
                data = entry.payload.get("d") \
                    if isinstance(entry.payload.get("d"), dict) else {}
                await asyncio.to_thread(
                    self.login_wal.record_rejected_event,
                    self.config.account_key, str(entry.payload.get("t") or "UNKNOWN"),
                    str(data.get("id") or ""), entry.sequence, exception.error_code,
                    self.config.rejected_event_max_records)
                await asyncio.to_thread(
                    self.inbound_wal.mark_dead, entry, exception.error_code)
                logger.error("QQ inbound event permanently rejected errorCode=%s",
                             exception.error_code)
                return True
            except Exception as persistence_exception:
                return await self._reschedule_inbound(entry, persistence_exception)
        except Exception as exception:
            return await self._reschedule_inbound(entry, exception)
        await asyncio.to_thread(self.inbound_wal.complete, entry)
        return True

    async def _reschedule_inbound(self, entry: InboundWalEntry,
                                  exception: Exception) -> bool:
        """记录安全错误码并执行不阻塞其他事件的有限指数退避。"""
        error_code = exception.error_code \
            if isinstance(exception, InternalHttpError) else type(exception).__name__.upper()[:64]
        delay = min(self.config.inbound_retry_max_seconds, 2 ** entry.attempt)
        rescheduled = await asyncio.to_thread(
            self.inbound_wal.reschedule, entry, time.time() + delay,
            error_code, self.config.inbound_max_attempts)
        self._inbound_worker_error_code = error_code
        if rescheduled is None:
            logger.error("QQ inbound event moved to dead attempt=%s errorCode=%s",
                         entry.attempt + 1, error_code)
            return True
        logger.warning("QQ inbound event retry scheduled attempt=%s errorCode=%s",
                       rescheduled.attempt, error_code)
        return False

    async def run_login_worker(self, stop: asyncio.Event) -> None:
        """持续恢复和处理已持久化的登录命令。"""
        scan_failures = 0
        self._login_worker_running = True
        try:
            while not stop.is_set():
                self._login_worker_last_tick = time.monotonic()
                # 与入站 worker 相同，扫描期间产生的唤醒不能被扫描后清除。
                self._login_wakeup.clear()
                try:
                    entries = await asyncio.to_thread(
                        self.login_wal.load_batch, time.time(), LOGIN_WAL_BATCH_SIZE)
                    scan_failures = 0
                    self._login_worker_error_code = None
                    if entries:
                        self._login_worker_active = True
                        try:
                            await self._process_login_entry(entries[0])
                        finally:
                            self._login_worker_active = False
                        continue
                except asyncio.CancelledError:
                    raise
                except Exception as exception:
                    scan_failures += 1
                    self._login_worker_error_code = type(exception).__name__[:128]
                    logger.error("QQ login WAL worker failed attempt=%s errorCode=%s",
                                 scan_failures, self._login_worker_error_code)
                    if scan_failures >= self.config.login_max_attempts:
                        raise LoginWalUnavailableError(
                            "QQ login WAL worker exhausted") from exception
                delay = min(self.config.login_retry_max_seconds,
                            2 ** max(0, scan_failures - 1)) \
                    if scan_failures else self.config.login_worker_poll_seconds
                try:
                    await asyncio.wait_for(self._login_wakeup.wait(), timeout=delay)
                except TimeoutError:
                    pass
        finally:
            self._login_worker_active = False
            self._login_worker_running = False

    async def command_health(self) -> dict[str, Any]:
        """返回不包含消息或凭据的 Gateway 与登录队列健康状态。"""
        try:
            counts = await asyncio.to_thread(self.login_wal.stats)
            rejected_count = await asyncio.to_thread(self.login_wal.rejected_count)
        except Exception as exception:
            self._login_worker_error_code = type(exception).__name__[:128]
            counts = {"pending": -1, "done": -1, "dead": -1}
            rejected_count = -1
        try:
            inbound_counts = await asyncio.to_thread(self.inbound_wal.stats)
        except Exception as exception:
            self._inbound_worker_error_code = type(exception).__name__[:128]
            inbound_counts = {"pending": -1, "processing": -1, "done": -1,
                              "dead": -1, "payloadBytes": -1}
        now = time.monotonic()
        gateway_fresh = self._gateway_ready and self._gateway_last_activity > 0 \
            and now - self._gateway_last_activity <= self.config.gateway_stale_seconds
        worker_fresh = self._login_worker_running and self._login_worker_last_tick > 0 \
            and (self._login_worker_active
                 or now - self._login_worker_last_tick
                 <= max(30.0, self.config.login_worker_poll_seconds * 4))
        inbound_worker_fresh = self._inbound_worker_running \
            and self._inbound_worker_last_tick > 0 \
            and (self._inbound_worker_active
                 or now - self._inbound_worker_last_tick
                 <= max(30.0, self.config.inbound_worker_poll_seconds * 4))
        raw_dead_login_commands = counts.get("dead", -1)
        dead_login_commands = raw_dead_login_commands \
            if isinstance(raw_dead_login_commands, int) \
            and not isinstance(raw_dead_login_commands, bool) \
            and raw_dead_login_commands >= 0 else -1
        login_commands_healthy = dead_login_commands == 0
        login_command_error_code = None
        if dead_login_commands > 0:
            login_command_error_code = "DEAD_LOGIN_COMMANDS"
        elif dead_login_commands < 0:
            login_command_error_code = "LOGIN_WAL_UNAVAILABLE"
        # 入站历史拒绝仅作诊断；登录死亡命令代表未闭环的用户请求，必须降级命令健康。
        inbound_healthy = inbound_worker_fresh and self._inbound_worker_error_code is None
        healthy = gateway_fresh and worker_fresh and inbound_healthy \
            and self._login_worker_error_code is None and login_commands_healthy
        return {
            "status": "UP" if healthy else "DOWN",
            "gatewayReady": gateway_fresh,
            "loginWorkerReady": worker_fresh and self._login_worker_error_code is None,
            "loginCommandsReady": login_commands_healthy,
            "inboundReady": inbound_healthy,
            "pendingLoginCommands": counts.get("pending", -1),
            "deadLoginCommands": dead_login_commands,
            "rejectedInboundEvents": rejected_count,
            "pendingInboundEvents": inbound_counts.get("pending", -1),
            "processingInboundEvents": inbound_counts.get("processing", -1),
            "deadInboundEvents": inbound_counts.get("dead", -1),
            "inboundPayloadBytes": inbound_counts.get("payloadBytes", -1),
            "gatewayErrorCode": self._gateway_error_code,
            "loginWorkerErrorCode": self._login_worker_error_code,
            "loginCommandErrorCode": login_command_error_code,
            "inboundWorkerErrorCode": self._inbound_worker_error_code,
        }

    async def _process_login_entry(self, entry: LoginWalEntry) -> None:
        """处理一条命令，并把每个恢复点原子写回 WAL。"""
        current = entry
        try:
            if current.phase == "FAILURE_REPLY":
                await self.send_text(current.sender_id,
                                     current.message_id,
                                     "QQ 登录二维码生成失败，请稍后重试。", 2)
                # 失败终态虽已通知用户，仍保留 DEAD 可见性，避免登录链路故障被健康检查静默掩盖。
                await asyncio.to_thread(
                    self.login_wal.mark_dead, current,
                    current.last_error_code or "LOGIN_EXECUTION_FAILED")
                return
            if time.time() >= current.deadline_at:
                raise PermanentLoginCommandError("QQ login command deadline exceeded")
            task_id = current.task_instance_id
            if task_id is None:
                task_id = await self._create_relogin_task(
                    current.message_id, current.generation)
                current = await asyncio.to_thread(
                    self.login_wal.attach_task, current, task_id)
            relogin_status, requested_at = await self._wait_relogin_task(
                task_id, current.deadline_at)
            if relogin_status == "ALREADY_ONLINE":
                await self.send_text(current.sender_id, current.message_id,
                                     "QQ 当前已登录，无需扫码。", 2)
                await asyncio.to_thread(self.login_wal.complete, current)
                return
            image = await self._qr_bytes(requested_at)
            await self.send_image(current.sender_id, current.message_id, image,
                                  "已生成 QQ 登录二维码，请使用手机 QQ 扫码登录。", 2)
            await asyncio.to_thread(self.login_wal.complete, current)
        except asyncio.CancelledError:
            raise
        except PermanentLoginCommandError as exception:
            await self._record_login_failure(current, exception, retryable=False)
        except InternalHttpError as exception:
            await self._record_login_failure(current, exception,
                                             retryable=exception.retryable)
        except Exception as exception:
            await self._record_login_failure(current, exception, retryable=True)

    async def _record_login_failure(self, entry: LoginWalEntry, exception: Exception,
                                    retryable: bool) -> None:
        """按阶段记录安全错误码、有限退避或 DEAD 状态。"""
        now = time.time()
        attempt = entry.attempt + 1
        error_code = type(exception).__name__.upper()[:64]
        deadline_reached = now >= entry.deadline_at
        if entry.phase == "EXECUTE" and (
                not retryable or deadline_reached
                or attempt >= self.config.login_max_attempts):
            await asyncio.to_thread(
                self.login_wal.begin_failure_reply, entry, error_code)
            self._login_wakeup.set()
            logger.error("QQ login execution exhausted attempt=%s errorCode=%s",
                         attempt, error_code)
            return
        if entry.phase == "FAILURE_REPLY" \
                and attempt >= self.config.login_max_attempts:
            await asyncio.to_thread(self.login_wal.mark_dead, entry, error_code)
            logger.error("QQ login failure reply dead attempt=%s errorCode=%s",
                         attempt, error_code)
            return
        delay = min(self.config.login_retry_max_seconds, 2 ** (attempt - 1))
        next_attempt_at = now + delay
        if entry.phase == "EXECUTE":
            # 执行阶段不得因退避越过绝对截止时间后继续沉睡。
            next_attempt_at = min(next_attempt_at, entry.deadline_at)
        await asyncio.to_thread(
            self.login_wal.reschedule, entry, next_attempt_at, error_code)
        logger.warning("QQ login command retry scheduled phase=%s attempt=%s errorCode=%s",
                       entry.phase, attempt, error_code)

    async def _create_relogin_task(self, message_id: str, generation: int) -> str:
        """使用稳定幂等键创建或取得 OneBot 重登录任务。"""
        if isinstance(generation, bool) or not isinstance(generation, int) \
                or generation < 0 or generation > 999_999_999:
            raise PermanentLoginCommandError("OneBot relogin generation is invalid")
        try:
            message_digest = hashlib.sha256(message_id.encode("utf-8")).hexdigest()
        except UnicodeEncodeError as exception:
            raise PermanentLoginCommandError(
                "OneBot relogin message identity is invalid") from exception
        scheduler_identity = f"qq-login:{message_digest}:g{generation}"
        request_id = f"qq_{message_digest}_g{generation}"
        task = await self._internal_json("POST", self.config.scheduler_url + "/api/v1/task-instances", {
            "taskName": "onebot_relogin",
            "idempotencyKey": scheduler_identity,
            "businessType": "ONEBOT_RELOGIN", "businessId": scheduler_identity,
            "parentTaskInstanceId": None, "priority": 90,
            "parameters": {"accountKey": self.config.onebot_account_key, "requestId": request_id},
            "requiredNodeLabels": {}}, "")
        task_id = str(task.get("id") or "")
        if not task_id or len(task_id) > 128:
            raise PermanentLoginCommandError("OneBot relogin task has no valid id")
        return task_id

    async def _wait_relogin_task(self, task_id: str,
                                 deadline_at: float) -> tuple[str, str]:
        """在绝对截止时间内轮询 OneBot 重登录任务。"""
        terminal_failures = {"FAILED", "CANCELLED", "TIMED_OUT"}
        active_statuses = {"CREATED", "QUEUED", "RUNNING", "WAITING_CHILDREN", "CANCELLING"}
        while True:
            self._login_worker_last_tick = time.monotonic()
            remaining = deadline_at - time.time()
            if remaining <= 0:
                raise PermanentLoginCommandError("OneBot relogin task timed out")
            try:
                async with asyncio.timeout(remaining):
                    state = await self._internal_json("GET",
                        self.config.scheduler_url + f"/api/v1/task-instances/{task_id}",
                        None, "")
            except TimeoutError as exception:
                raise PermanentLoginCommandError(
                    "OneBot relogin task timed out") from exception
            status = str(state.get("status") or "")
            if status == "SUCCEEDED":
                remaining = deadline_at - time.time()
                if remaining <= 0:
                    raise PermanentLoginCommandError("OneBot relogin task timed out")
                try:
                    async with asyncio.timeout(remaining):
                        results = await self._internal_json("GET",
                            self.config.scheduler_url
                            + f"/api/v1/task-instances/{task_id}/results", None, "")
                except TimeoutError as exception:
                    raise PermanentLoginCommandError(
                        "OneBot relogin task timed out") from exception
                result = next((step.get("result") for step in reversed(results.get("steps", []))
                               if step.get("status") == "SUCCEEDED" and isinstance(step.get("result"), dict)), None)
                result_status = str(result.get("status") or "") if result else ""
                if result_status == "ALREADY_ONLINE":
                    return result_status, ""
                if result_status != "QR_READY":
                    raise PermanentLoginCommandError(
                        "OneBot relogin task returned an invalid result status")
                requested_at = str(result.get("requestedAt") or "") if result else ""
                if not requested_at or len(requested_at) > 128:
                    raise PermanentLoginCommandError(
                        "OneBot relogin task returned no valid result")
                return result_status, requested_at
            if status in terminal_failures:
                raise PermanentLoginCommandError(
                    "OneBot relogin task ended without a QR")
            if status not in active_statuses:
                raise PermanentLoginCommandError("OneBot relogin task status is invalid")
            await asyncio.sleep(min(0.5, max(0.0, remaining)))

    async def _qr_bytes(self, requested_at: str) -> bytes:
        headers = {"Authorization": f"Bearer {self.config.onebot_token}"}
        async with self.session.post(self.config.onebot_url +
                "/internal/v1/control/login-qr/content", headers=headers,
                json={"accountKey": self.config.onebot_account_key,
                      "requestedAt": requested_at}, timeout=INTERNAL_HTTP_TIMEOUT) as response:
            data = await response.read()
            if response.status != 200 or response.headers.get("Content-Type", "").split(";")[0] != "image/png" \
                    or not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data) > 2 * 1024 * 1024:
                raise RuntimeError("OneBot Connector returned an invalid QR")
            return data

    async def send_image(self, sender: str, message_id: str, image: bytes, text: str,
                         sequence: int = 1) -> None:
        """上传并被动回复一张有界图片。"""
        if not image or len(image) > 2 * 1024 * 1024 or sequence < 1 or sequence > 10:
            raise ValueError("QQ image size is invalid")
        upload = await self._qq_request("POST", f"/v2/users/{sender}/files", {
            "file_type": 1, "srv_send_msg": False,
            "file_data": base64.b64encode(image).decode("ascii")})
        file_info = str(upload.get("file_info") or "")
        if not file_info:
            raise RuntimeError("QQ image upload returned no file_info")
        path = f"/v2/users/{sender}/messages"
        active = {"msg_type": 7, "media": {"file_info": file_info}, "content": text,
                  "msg_seq": random.randint(1, 65535)}
        passive = {**active, "msg_id": message_id, "msg_seq": sequence}
        try:
            await self._qq_request("POST", path, passive)
        except RuntimeError as exception:
            if "code=40054005" in str(exception):
                return
            if not any(code in str(exception)
                       for code in ("code=40034024", "code=40034005")):
                raise
            # 上传只执行一次；被动窗口过期后复用 file_info 做一次主动发送。
            # 主动图片没有可证明的平台幂等键，拒绝结果必须交给登录 WAL 有界重试。
            await self._qq_request("POST", path, active)

    async def send_text(self, target: str, message_id: str, text: str, sequence: int = 1,
                        event_type: str = "C2C_MESSAGE_CREATE",
                        active_only: bool = False) -> None:
        """发送一条有界文本消息，并按需跳过被动回复。"""
        if not text or len(text) > 2000 or sequence < 1 or sequence > 10:
            raise ValueError("QQ text size is invalid")
        if event_type == "GROUP_AT_MESSAGE_CREATE":
            path = f"/v2/groups/{target}/messages"
        elif event_type in {"AT_MESSAGE_CREATE", "DIRECT_MESSAGE_CREATE"}:
            path = f"/channels/{target}/messages"
        else:
            path = f"/v2/users/{target}/messages"
        active = {"content": text}
        if event_type not in {"AT_MESSAGE_CREATE", "DIRECT_MESSAGE_CREATE"}:
            active["msg_type"] = 0
            # 官方 C2C/群消息协议要求主动发送携带序号；每次尝试生成新值且不作为幂等证明。
            active["msg_seq"] = random.randint(1, 65535)
        if active_only:
            # 进度和超出被动序号范围的分页走主动消息，避免同一入站消息的序号碰撞。
            await self._qq_request("POST", path, active)
            return
        passive = {**active, "msg_id": message_id}
        if event_type not in {"AT_MESSAGE_CREATE", "DIRECT_MESSAGE_CREATE"}:
            passive["msg_seq"] = sequence
        try:
            await self._qq_request("POST", path, passive)
        except RuntimeError as exception:
            if "code=40054005" in str(exception):
                # 同一msg_seq已成功投递时，腾讯会把重试标记为重复；该结果应按幂等成功处理。
                return
            # QQ 对被动回复窗口过期会返回两种错误码，均应降级为主动消息。
            if not any(code in str(exception) for code in ("code=40034024", "code=40034005")):
                raise
            # 主动消息没有可证明的平台幂等键，任何拒绝都必须显式失败并按预算重试。
            await self._qq_request("POST", path, active)

    async def _qq_request(self, method: str, path: str,
                          payload: dict | None = None) -> dict:
        for request_attempt in range(2):
            token = await self.access_token()
            headers = {"Authorization": f"QQBot {token}",
                       "X-Union-Appid": self.config.app_id}
            async with self.session.request(method, self.config.api_base_url + path,
                                            headers=headers, json=payload,
                                            timeout=QQ_HTTP_TIMEOUT) as response:
                response_error = None
                try:
                    body = await response.json(content_type=None)
                except asyncio.CancelledError:
                    raise
                except Exception as exception:
                    body = None
                    response_error = exception
                if response.status == 401:
                    # 明确的未授权响应证明本次请求未被受理；刷新后只重放一次。
                    self._invalidate_access_token(token)
                    if request_attempt == 0:
                        continue
                if response_error is not None:
                    if response.status == 401:
                        raise RuntimeError(
                            "QQ request failed with HTTP 401, code=unknown, "
                            "reason=request rejected") from response_error
                    raise RuntimeError(
                        "QQ request returned an invalid response") from response_error
                if response.status >= 400 or not isinstance(body, dict):
                    code = str(body.get("code") or "unknown") \
                        if isinstance(body, dict) else "unknown"
                    reason = str(body.get("message") or body.get("msg")
                                 or "request rejected")[:160] \
                        if isinstance(body, dict) else "request rejected"
                    raise RuntimeError(
                        f"QQ request failed with HTTP {response.status}, "
                        f"code={code}, reason={reason}")
                return body
        raise RuntimeError("QQ request failed after access token refresh")

    async def _internal_json(self, method: str, url: str, payload: dict | None,
                             token: str) -> dict:
        headers = self._internal_headers(url, token)
        async with self.session.request(method, url, headers=headers, json=payload,
                                        timeout=INTERNAL_HTTP_TIMEOUT) as response:
            if response.status >= 400:
                # 错误正文可能含下游内部细节，仅消费而不持久化或记录。
                await response.read()
                raise InternalHttpError(response.status)
            try:
                body = await response.json(content_type=None)
            except asyncio.CancelledError:
                raise
            except Exception as exception:
                # 成功状态上的截断或非 JSON 正文属于下游协议故障，不得误判为本地坏消息并推进游标。
                raise RuntimeError("internal request returned an invalid response") from exception
            if not isinstance(body, dict):
                raise RuntimeError("internal request returned an invalid response")
            return body

    def _internal_headers(self, url: str, token: str) -> dict[str, str]:
        """构造内部调用头，并为调度器附加业务身份。"""
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if url.startswith(self.config.scheduler_url) and self.config.scheduler_token:
            # 调度器业务接口要求连接器携带独立服务身份。
            headers["X-Task-Service-Id"] = "qq-connector-service"
            headers["X-Task-Business-Token"] = self.config.scheduler_token
        return headers

    async def connected(self, stop: asyncio.Event) -> None:
        """运行一次可恢复 Gateway 会话。"""
        self._gateway_ready = False
        token = await self.access_token()
        async with self.session.ws_connect(await self.gateway_url(), heartbeat=None,
                autoping=True, max_msg_size=16 * 1024 * 1024) as websocket:
            hello = await websocket.receive_json(timeout=20)
            if int(hello.get("op", -1)) != 10:
                raise RuntimeError("QQ gateway did not send HELLO")
            interval = float(hello.get("d", {}).get("heartbeat_interval", 45000)) / 1000
            last_gateway_frame_at = time.monotonic()
            heartbeat_sent = False
            if self._session_id and self._sequence is not None:
                await websocket.send_json({"op": 6, "d": {"token": f"QQBot {token}",
                    "session_id": self._session_id, "seq": self._sequence}})
            else:
                await websocket.send_json({"op": 2, "d": {"token": f"QQBot {token}",
                    "intents": self.config.intents, "shard": [0, 1],
                    "properties": {"$os": "linux", "$browser": "mytools",
                                   "$device": "mytools"}}})

            async def heartbeat() -> None:
                nonlocal heartbeat_sent
                while not stop.is_set() and not websocket.closed:
                    await asyncio.sleep(interval * random.uniform(0.9, 1.0))
                    # 心跳已发出后若连续两个周期没有任何服务端帧，主动重建连接。
                    if heartbeat_sent \
                            and time.monotonic() - last_gateway_frame_at > interval * 2:
                        raise RuntimeError("QQ gateway heartbeat acknowledgement timed out")
                    await websocket.send_json({"op": 1, "d": self._sequence})
                    heartbeat_sent = True

            heartbeat_task = asyncio.create_task(heartbeat())

            async def receive_messages() -> None:
                nonlocal last_gateway_frame_at
                async for message in websocket:
                    if message.type != aiohttp.WSMsgType.TEXT:
                        continue
                    payload = json.loads(message.data)
                    last_gateway_frame_at = time.monotonic()
                    self._gateway_last_activity = time.monotonic()
                    opcode = int(payload.get("op", -1))
                    if opcode == 0:
                        await self._handle_dispatch(payload)
                    elif opcode == 11:
                        # Gateway 心跳确认同时作为健康新鲜度信号。
                        self._gateway_error_code = None
                    elif opcode == 7:
                        raise RuntimeError("QQ gateway requested reconnect")
                    elif opcode == 9:
                        previous_session = self._session_id
                        if previous_session:
                            # 先持久删除不可恢复 checkpoint，再切换为全新 Identify。
                            try:
                                await asyncio.to_thread(
                                    self.inbound_wal.invalidate_checkpoint,
                                    previous_session)
                            except (InboundWalError, OSError) as exception:
                                raise InboundWalUnavailableError(
                                    "QQ Gateway checkpoint invalidation failed") from exception
                        self._session_id = ""
                        self._sequence = None
                        raise RuntimeError("QQ gateway invalidated the session")

            receive_task = asyncio.create_task(receive_messages())
            try:
                completed, _ = await asyncio.wait(
                    {heartbeat_task, receive_task}, return_when=asyncio.FIRST_COMPLETED)
                if heartbeat_task in completed:
                    heartbeat_task.result()
                    if not stop.is_set():
                        raise RuntimeError("QQ gateway heartbeat stopped unexpectedly")
                if receive_task in completed:
                    receive_task.result()
            finally:
                self._gateway_ready = False
                heartbeat_task.cancel()
                receive_task.cancel()
                await asyncio.gather(heartbeat_task, receive_task,
                                     return_exceptions=True)

    async def _handle_dispatch(self, payload: dict[str, Any]) -> None:
        """先持久化 Dispatch 与 checkpoint，再推进内存恢复游标。"""
        event_type = str(payload.get("t") or "")
        data = payload.get("d") if isinstance(payload.get("d"), dict) else {}
        incoming_sequence = (int(payload["s"])
                             if payload.get("s") is not None else None)
        if event_type == "READY":
            session_id = str(data.get("session_id") or "")
            if not session_id or incoming_sequence is None:
                raise RuntimeError("QQ gateway READY has no resumable checkpoint")
            await self._save_gateway_checkpoint(session_id, incoming_sequence)
            self._session_id = session_id
            self._sequence = incoming_sequence
            self._gateway_ready = bool(self._session_id)
            self._gateway_last_activity = time.monotonic()
            self._gateway_error_code = None
            logger.info("QQ gateway ready")
        elif event_type == "RESUMED":
            if not self._session_id or incoming_sequence is None:
                raise RuntimeError("QQ gateway RESUMED has no resumable checkpoint")
            await self._save_gateway_checkpoint(self._session_id, incoming_sequence)
            self._sequence = incoming_sequence
            self._gateway_ready = True
            self._gateway_last_activity = time.monotonic()
            self._gateway_error_code = None
            logger.info("QQ gateway session resumed")
        else:
            logger.info("QQ gateway dispatch received: %s", event_type or "UNKNOWN")
            if not self._session_id or incoming_sequence is None:
                raise RuntimeError("QQ gateway Dispatch has no resumable checkpoint")
            if event_type in SUPPORTED_MESSAGE_EVENTS:
                try:
                    inbound_event = self.inbound_wal.create_event(
                        self.config.account_key, self._session_id,
                        incoming_sequence, payload)
                except (RecursionError, OverflowError, ValueError) as exception:
                    # 无法表示为有界 JSON 的单帧属于永久输入错误，先写安全审计再允许游标前进。
                    try:
                        await asyncio.to_thread(
                            self.login_wal.record_rejected_event,
                            self.config.account_key, event_type,
                            str(data.get("id") or ""), incoming_sequence,
                            "INVALID_GATEWAY_PAYLOAD",
                            self.config.rejected_event_max_records)
                    except Exception as persistence_exception:
                        raise LoginWalUnavailableError(
                            "QQ rejected event persistence failed") from persistence_exception
                    logger.error(
                        "QQ gateway payload permanently rejected errorCode=%s",
                        type(exception).__name__[:128])
                else:
                    try:
                        await asyncio.to_thread(self.inbound_wal.store, inbound_event)
                    except (InboundWalError, OSError) as exception:
                        raise InboundWalUnavailableError(
                            "QQ inbound event persistence failed") from exception
                await self._save_gateway_checkpoint(self._session_id, incoming_sequence)
                self._sequence = incoming_sequence
                self._inbound_wakeup.set()
                return
            # 非消息 Dispatch 无需保留正文，但 checkpoint 仍必须先落盘。
            await self._save_gateway_checkpoint(self._session_id, incoming_sequence)
            self._sequence = incoming_sequence

    async def _save_gateway_checkpoint(self, session_id: str, sequence: int) -> None:
        """保存 Gateway checkpoint，并把本机存储故障升级为进程级故障。"""
        try:
            await asyncio.to_thread(
                self.inbound_wal.save_checkpoint, session_id, sequence,
                self.config.account_key)
        except (InboundWalError, OSError) as exception:
            raise InboundWalUnavailableError(
                "QQ Gateway checkpoint persistence failed") from exception

    async def run(self, stop: asyncio.Event) -> None:
        """持续重连官方 QQ Gateway。"""
        backoff = 1.0
        while not stop.is_set():
            try:
                await self.connected(stop)
                backoff = 1.0
            except asyncio.CancelledError:
                raise
            except (LoginWalUnavailableError, InboundWalUnavailableError):
                # 稳定存储不可用时退出进程，禁止在健康检查中伪装可用。
                raise
            except Exception as exception:
                self._gateway_ready = False
                self._gateway_error_code = type(exception).__name__[:128]
                logger.warning("QQ gateway disconnected errorCode=%s",
                               self._gateway_error_code)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 60.0)
