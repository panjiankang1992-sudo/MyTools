"""提供有界的 NapCat OneBot API 与内容连接能力。"""

from __future__ import annotations

import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import socket
from urllib.parse import unquote, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

from .models import Account, ContentSource

CHUNK_BYTES = 1024 * 1024


class SafeRedirectHandler(HTTPRedirectHandler):
    """重新校验重定向并防止 OneBot 认证信息泄漏。"""

    def __init__(self, validator):
        self._validator = validator
        super().__init__()

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """仅跟随公网 HTTPS 重定向并移除渠道认证信息。"""
        self._validator(newurl)
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None:
            redirected.remove_header("Authorization")
        return redirected


class OneBotClient:
    """仅调用固定的 get_file 动作并受控传输结果。"""

    def __init__(self, secret_resolver=None, opener=None, resolver=socket.getaddrinfo) -> None:
        self._secret_resolver = secret_resolver or resolve_secret
        self._opener = opener
        self._resolver = resolver

    def get_file(self, account: Account, provider_file_id: str) -> dict:
        """通过固定的 OneBot get_file 动作解析一个不透明文件标识。"""
        token = self._secret_resolver(account.secret_ref)
        body = json.dumps({"file": provider_file_id, "file_id": provider_file_id},
                          separators=(",", ":")).encode()
        request = Request(account.http_base_url.rstrip("/") + "/get_file", data=body, method="POST",
                          headers={"Authorization": f"Bearer {token}",
                                   "Content-Type": "application/json", "Accept": "application/json"})
        opener = self._opener or build_opener().open
        with opener(request, timeout=30) as response:
            if getattr(response, "status", 200) >= 400:
                raise RuntimeError("OneBot get_file request failed")
            raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise RuntimeError("OneBot get_file response is too large")
            payload = json.loads(raw.decode("utf-8"))
        if not isinstance(payload, dict) or payload.get("status") not in (None, "ok") \
                or int(payload.get("retcode", 0)) != 0 or not isinstance(payload.get("data"), dict):
            raise RuntimeError("OneBot get_file returned an invalid response")
        return payload["data"]

    def send_text(self, account: Account, message_type: str, target_id: str, text: str) -> dict:
        """通过固定 OneBot 动作发送有界文本消息。"""
        if message_type not in {"private", "group"} or not target_id.isdigit() \
                or not text or len(text) > 4000:
            raise ValueError("OneBot text message is invalid")
        action = "/send_private_msg" if message_type == "private" else "/send_group_msg"
        target_key = "user_id" if message_type == "private" else "group_id"
        return self._json_action(account, action, {target_key: int(target_id), "message": text})

    def get_forward_message(self, account: Account, forward_id: str) -> list:
        """通过固定动作读取一条合并转发消息。"""
        if not forward_id or len(forward_id) > 512:
            raise ValueError("OneBot forward id is invalid")
        data = self._json_action(account, "/get_forward_msg", {"message_id": forward_id})
        messages = data.get("messages") if isinstance(data, dict) else None
        if not isinstance(messages, list):
            raise RuntimeError("OneBot get_forward_msg returned no messages")
        return messages

    def _json_action(self, account: Account, path: str, payload: dict) -> dict:
        """调用受控 OneBot JSON 动作并校验通用响应。"""
        token = self._secret_resolver(account.secret_ref)
        request = Request(account.http_base_url.rstrip("/") + path,
                          data=json.dumps(payload, separators=(",", ":")).encode(), method="POST",
                          headers={"Authorization": f"Bearer {token}",
                                   "Content-Type": "application/json", "Accept": "application/json"})
        opener = self._opener or build_opener().open
        with opener(request, timeout=30) as response:
            raw = response.read(4 * 1024 * 1024 + 1)
            if getattr(response, "status", 200) >= 400 or len(raw) > 4 * 1024 * 1024:
                raise RuntimeError("OneBot action request failed")
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict) or value.get("status") not in (None, "ok") \
                or int(value.get("retcode", 0)) != 0:
            raise RuntimeError("OneBot action returned an invalid response")
        data = value.get("data")
        return data if isinstance(data, dict) else {}

    def prepare(self, account: Account, provider_file_id: str) -> ContentSource:
        """优先使用安全映射的本地文件，否则校验返回的 URL。"""
        data = self.get_file(account, provider_file_id)
        returned_path = str(data.get("file") or "").strip()
        if returned_path:
            local = mapped_local_file(account, returned_path)
            if local is not None:
                return ContentSource(account, local_path=str(local))
        url = str(data.get("url") or "").strip()
        if not url:
            raise RuntimeError("OneBot get_file returned no usable content source")
        validate_stream_url(url, account.http_base_url, self._resolver)
        return ContentSource(account, url=url)

    def public_url(self, source: ContentSource) -> str | None:
        """仅返回稳定且不包含凭据的公网 HTTPS URL。"""
        if source.url is None:
            return None
        parsed = urlparse(source.url)
        if parsed.scheme != "https" or parsed.query or parsed.fragment or parsed.username or parsed.password:
            return None
        validate_public_url(source.url, self._resolver)
        return source.url

    def stream(self, source: ContentSource, output, maximum_bytes: int) -> int:
        """使用严格字节上限传输已准备的本地或 HTTP 内容源。"""
        if source.local_path is not None:
            path = Path(source.local_path)
            if path.stat().st_size > maximum_bytes:
                raise ValueError("OneBot local content exceeds maximumBytes")
            with path.open("rb") as response:
                return copy_bounded(response, output, maximum_bytes)
        if source.url is None:
            raise RuntimeError("OneBot content source is missing")
        token = self._secret_resolver(source.account.secret_ref)
        local_api = same_authority(source.url, source.account.http_base_url)
        headers = {"Accept": "application/octet-stream"}
        if local_api:
            headers["Authorization"] = f"Bearer {token}"
        request = Request(source.url, headers=headers)
        opener = self._opener or build_opener(
            SafeRedirectHandler(lambda value: validate_public_url(value, self._resolver))).open
        with opener(request, timeout=60) as response:
            declared = response.headers.get("Content-Length")
            if declared is not None and int(declared) > maximum_bytes:
                raise ValueError("OneBot remote content exceeds maximumBytes")
            return copy_bounded(response, output, maximum_bytes)


def resolve_secret(secret_ref: str) -> str:
    """解析环境变量引用且不向调用方暴露引用名称。"""
    if not secret_ref.startswith("env://"):
        raise ValueError("OneBot secret reference must use env scheme")
    name = secret_ref.removeprefix("env://")
    if not name or not name.replace("_", "A").isalnum() or not name.upper() == name:
        raise ValueError("OneBot secret environment name is invalid")
    value = os.environ.get(name, "")
    if not value:
        raise ValueError("OneBot secret material is missing")
    return value


def mapped_local_file(account: Account, returned_path: str) -> Path | None:
    """将容器 QQ 路径映射到宿主机根目录并阻止路径穿越。"""
    value = unquote(urlparse(returned_path).path) if returned_path.startswith("file://") else returned_path
    container = PurePosixPath(account.container_qq_root)
    candidate = PurePosixPath(value)
    try:
        relative = candidate.relative_to(container)
    except ValueError:
        host_candidate = Path(value)
    else:
        host_candidate = Path(account.host_qq_root).joinpath(*relative.parts)
    root = Path(account.host_qq_root).resolve()
    try:
        resolved = host_candidate.resolve()
    except OSError:
        return None
    if not resolved.is_relative_to(root) or not resolved.is_file():
        return None
    return resolved


def validate_public_url(url: str, resolver=socket.getaddrinfo) -> None:
    """要求 HTTPS URL 不含凭据且仅解析到公网地址。"""
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password \
            or parsed.fragment:
        raise ValueError("OneBot public URL is invalid")
    addresses = resolver(parsed.hostname, parsed.port or 443, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses):
        raise ValueError("OneBot public URL resolves to a non-public address")


def validate_stream_url(url: str, base_url: str, resolver=socket.getaddrinfo) -> None:
    """仅允许已配置的本机 OneBot API 或公网 HTTPS 内容。"""
    parsed = urlparse(url)
    if parsed.username or parsed.password or parsed.fragment or not parsed.hostname:
        raise ValueError("OneBot content URL is invalid")
    if same_authority(url, base_url):
        return
    validate_public_url(url, resolver)


def same_authority(left: str, right: str) -> bool:
    """比较规范化后的协议、主机和有效端口。"""
    first, second = urlparse(left), urlparse(right)
    effective = lambda value: value.port or (443 if value.scheme == "https" else 80)
    try:
        return (first.scheme, first.hostname, effective(first)) == \
            (second.scheme, second.hostname, effective(second))
    except ValueError:
        return False


def copy_bounded(source, output, maximum_bytes: int) -> int:
    """复制内容流并在超过字节上限后立即停止。"""
    total = 0
    while chunk := source.read(CHUNK_BYTES):
        total += len(chunk)
        if total > maximum_bytes:
            raise ValueError("OneBot content exceeds maximumBytes")
        output.write(chunk)
    return total
