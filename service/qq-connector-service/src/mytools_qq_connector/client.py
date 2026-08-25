"""官方 QQ、Messaging、Scheduler 与 OneBot 原子客户端。"""
from __future__ import annotations

import asyncio
import base64
from datetime import UTC, datetime
import json
import logging
import random
import time
from typing import Any, Awaitable, Callable

import aiohttp

from .config import Config

logger = logging.getLogger(__name__)


class QQConnector:
    """维护官方 QQ Gateway，并把消息转交给内部服务。"""

    def __init__(self, config: Config, session: aiohttp.ClientSession) -> None:
        self.config = config
        self.session = session
        self._token = ""
        self._token_expires_at = 0.0
        self._session_id = ""
        self._sequence: int | None = None

    async def access_token(self) -> str:
        """按服务端有效期缓存官方 QQ 访问令牌。"""
        if self._token and time.monotonic() < self._token_expires_at - 60:
            return self._token
        async with self.session.post(self.config.token_url, json={
                "appId": self.config.app_id, "clientSecret": self.config.app_secret}) as response:
            body = await response.json(content_type=None)
            if response.status >= 400 or not body.get("access_token"):
                raise RuntimeError(f"QQ token request failed with HTTP {response.status}")
        self._token = str(body["access_token"])
        self._token_expires_at = time.monotonic() + int(body.get("expires_in", 7200))
        return self._token

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
        if event_type != "C2C_MESSAGE_CREATE" or not sender or not message_id:
            return
        await self._messaging_receive(payload, sender, message_id, content)
        if sender == self.config.allowed_sender and content in {"登录", "登陆"}:
            task = asyncio.create_task(self._relogin_and_reply(sender, message_id),
                                       name=f"qq-relogin-{message_id[:16]}")
            task.add_done_callback(self._log_background_failure)

    @staticmethod
    def _log_background_failure(task: asyncio.Task) -> None:
        """记录后台命令失败且不输出消息载荷或凭据。"""
        if task.cancelled():
            return
        exception = task.exception()
        if exception is not None:
            logger.error("QQ relogin command failed: %s", type(exception).__name__)

    async def _messaging_receive(self, payload: dict[str, Any], sender: str,
                                 message_id: str, content: str) -> None:
        parts = [{"type": "TEXT", "text": content or "[empty]", "attachmentType": None,
                  "providerFileId": None, "providerAccountKey": None, "sourceUrl": None,
                  "fileName": None, "mimeType": None, "declaredSize": None}]
        body = {"ownerId": self.config.owner_id, "channelType": "QQ",
                "externalId": f"{self.config.account_key}:C2C_MESSAGE_CREATE:{message_id}",
                "conversationKey": f"{self.config.account_key}:c2c:{sender}", "sender": sender,
                "subject": None, "body": content or "[empty]",
                "receivedAt": datetime.now(UTC).isoformat(), "parts": parts}
        await self._internal_json("POST", self.config.messaging_url + "/internal/v1/inbound-messages",
                                  body, self.config.messaging_token)

    async def _relogin_and_reply(self, sender: str, message_id: str) -> None:
        request_id = "qq_" + "".join(character for character in message_id if character.isalnum())[-100:]
        task = await self._internal_json("POST", self.config.scheduler_url + "/api/v1/task-instances", {
            "taskName": "onebot_relogin", "idempotencyKey": f"qq-login:{message_id}",
            "businessType": "ONEBOT_RELOGIN", "businessId": message_id,
            "parentTaskInstanceId": None, "priority": 90,
            "parameters": {"accountKey": self.config.onebot_account_key, "requestId": request_id},
            "requiredNodeLabels": {}}, "")
        task_id = str(task["id"])
        for _ in range(90):
            state = await self._internal_json("GET",
                self.config.scheduler_url + f"/api/v1/task-instances/{task_id}", None, "")
            status = str(state.get("status") or "")
            if status == "SUCCEEDED":
                results = await self._internal_json("GET",
                    self.config.scheduler_url + f"/api/v1/task-instances/{task_id}/results", None, "")
                result = next((step.get("result") for step in reversed(results.get("steps", []))
                               if step.get("status") == "SUCCEEDED" and isinstance(step.get("result"), dict)), None)
                if result is None:
                    raise RuntimeError("OneBot relogin task returned no result")
                image = await self._qr_bytes(str(result["requestedAt"]))
                await self.send_image(sender, message_id, image,
                                      "已生成 QQ 登录二维码，请使用手机 QQ 扫码登录。")
                return
            if status in {"FAILED", "CANCELLED", "TIMED_OUT"}:
                raise RuntimeError(f"OneBot relogin task ended with {status}")
            await asyncio.sleep(2)
        raise RuntimeError("OneBot relogin task status timed out")

    async def _qr_bytes(self, requested_at: str) -> bytes:
        headers = {"Authorization": f"Bearer {self.config.onebot_token}"}
        async with self.session.post(self.config.onebot_url +
                "/internal/v1/control/login-qr/content", headers=headers,
                json={"accountKey": self.config.onebot_account_key,
                      "requestedAt": requested_at}) as response:
            data = await response.read()
            if response.status != 200 or response.headers.get("Content-Type", "").split(";")[0] != "image/png" \
                    or not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data) > 2 * 1024 * 1024:
                raise RuntimeError("OneBot Connector returned an invalid QR")
            return data

    async def send_image(self, sender: str, message_id: str, image: bytes, text: str) -> None:
        """上传并被动回复一张有界图片。"""
        if not image or len(image) > 2 * 1024 * 1024:
            raise ValueError("QQ image size is invalid")
        upload = await self._qq_request("POST", f"/v2/users/{sender}/files", {
            "file_type": 1, "srv_send_msg": False,
            "file_data": base64.b64encode(image).decode("ascii")})
        file_info = str(upload.get("file_info") or "")
        if not file_info:
            raise RuntimeError("QQ image upload returned no file_info")
        await self._qq_request("POST", f"/v2/users/{sender}/messages", {
            "msg_type": 7, "media": {"file_info": file_info}, "content": text,
            "msg_id": message_id, "msg_seq": 1})

    async def _qq_request(self, method: str, path: str,
                          payload: dict | None = None) -> dict:
        token = await self.access_token()
        headers = {"Authorization": f"QQBot {token}", "X-Union-Appid": self.config.app_id}
        async with self.session.request(method, self.config.api_base_url + path,
                                        headers=headers, json=payload) as response:
            body = await response.json(content_type=None)
            if response.status >= 400 or not isinstance(body, dict):
                raise RuntimeError(f"QQ request failed with HTTP {response.status}")
            return body

    async def _internal_json(self, method: str, url: str, payload: dict | None,
                             token: str) -> dict:
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        async with self.session.request(method, url, headers=headers, json=payload) as response:
            body = await response.json(content_type=None)
            if response.status >= 400 or not isinstance(body, dict):
                raise RuntimeError(f"internal request failed with HTTP {response.status}")
            return body

    async def connected(self, stop: asyncio.Event) -> None:
        """运行一次可恢复 Gateway 会话。"""
        token = await self.access_token()
        async with self.session.ws_connect(await self.gateway_url(), heartbeat=None,
                autoping=True, max_msg_size=16 * 1024 * 1024) as websocket:
            hello = await websocket.receive_json(timeout=20)
            if int(hello.get("op", -1)) != 10:
                raise RuntimeError("QQ gateway did not send HELLO")
            interval = float(hello.get("d", {}).get("heartbeat_interval", 45000)) / 1000
            await websocket.send_json({"op": 2, "d": {"token": f"QQBot {token}",
                "intents": self.config.intents, "shard": [0, 1],
                "properties": {"$os": "linux", "$browser": "mytools", "$device": "mytools"}}})

            async def heartbeat() -> None:
                while not stop.is_set() and not websocket.closed:
                    await asyncio.sleep(interval * random.uniform(0.9, 1.0))
                    await websocket.send_json({"op": 1, "d": self._sequence})

            task = asyncio.create_task(heartbeat())
            try:
                async for message in websocket:
                    if message.type != aiohttp.WSMsgType.TEXT:
                        continue
                    payload = json.loads(message.data)
                    if payload.get("s") is not None:
                        self._sequence = int(payload["s"])
                    if int(payload.get("op", -1)) == 0:
                        await self.receive(payload)
                    elif int(payload.get("op", -1)) in {7, 9}:
                        raise RuntimeError("QQ gateway requested reconnect")
            finally:
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    async def run(self, stop: asyncio.Event) -> None:
        """持续重连官方 QQ Gateway。"""
        backoff = 1.0
        while not stop.is_set():
            try:
                await self.connected(stop)
                backoff = 1.0
            except asyncio.CancelledError:
                raise
            except Exception as exception:
                logger.warning("QQ gateway disconnected: %s", type(exception).__name__)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 60.0)
