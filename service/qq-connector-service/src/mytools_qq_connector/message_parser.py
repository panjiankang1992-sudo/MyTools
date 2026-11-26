"""只解析官方 QQ 已下发的消息树，不依赖第三方协议补取正文。"""
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
import json
import math
import mimetypes
import re
from typing import Any
from urllib.parse import urlsplit


# 兼容已有附件摘要文本；协议标签采用转义，避免混入业务标识符。
COMPOSITE_ATTACHMENT = re.compile(
    r"\[\u9644\u4ef6\d+\]\s+\u7c7b\u578b:(?P<type>\S+)\s+"
    r"\u6587\u4ef6\u540d:(?P<filename>.*?)\s+"
    r"(?:\u5c3a\u5bf8:\S+\s+)?\u5927\u5c0f:(?P<size>\S+)\s+"
    r"URL:(?P<url>https?://\S+)", re.MULTILINE)
HTTP_URL = re.compile(r"https?://[^\s<>\"']+")


@dataclass(frozen=True, slots=True)
class ParseLimits:
    """限制解析成本并为可见的不完整提示保留分段容量。"""

    depth: int = 32
    nodes: int = 4096
    parts: int = 500
    text_chars: int = 1_000_000
    json_chars: int = 2 * 1024 * 1024


@dataclass(frozen=True, slots=True)
class ParsedMessage:
    """保存有序消息分段、汇总正文及明确的完整性状态。"""

    body: str
    parts: list[dict[str, Any]]
    issues: tuple[str, ...]

    @property
    def complete(self) -> bool:
        """返回当前可读取的消息树是否全部解析成功。"""
        return not self.issues


def message_indices(node: dict[str, Any]) -> tuple[str, str]:
    """按官方事件的 message_scene.ext 读取自身索引和引用索引。"""
    own = node.get("msg_idx") if isinstance(node.get("msg_idx"), str) else ""
    reference = ""
    scene = node.get("message_scene")
    ext = scene.get("ext") if isinstance(scene, dict) else None
    if isinstance(ext, list):
        for value in ext:
            if not isinstance(value, str):
                continue
            key, separator, item = value.partition("=")
            if separator and item.strip():
                if key.strip() == "msg_idx":
                    own = item.strip()
                elif key.strip() == "ref_msg_idx":
                    reference = item.strip()
    return own, reference


def text_part(value: str) -> dict[str, Any]:
    """构造与 Messaging 内部协议一致的纯文本分段。"""
    return {"type": "TEXT", "text": value, "attachmentType": None,
            "providerFileId": None, "providerAccountKey": None, "sourceUrl": None,
            "fileName": None, "mimeType": None, "declaredSize": None}


def parse_size(value: Any) -> int | None:
    """解析文件大小，拒绝非有限值和超出下游 Long 范围的输入。"""
    if isinstance(value, bool):
        return None
    match = re.fullmatch(r"(\d+(?:\.\d+)?)\s*([KMGT]?B)?", str(value), re.IGNORECASE)
    if match is None:
        return None
    size = float(match[1]) * (1024 ** {"B": 0, "KB": 1, "MB": 2,
                                     "GB": 3, "TB": 4}[str(match[2] or "B").upper()])
    if not math.isfinite(size) or not 0 < size < 2 ** 63:
        return None
    rounded = round(size)
    return rounded if 0 < rounded < 2 ** 63 else None


class MessageParser:
    """按节点顺序展开官方 msg_elements，并保留引用边界和附件位置。"""

    def __init__(self, account_key: str, lookup: Callable[[str], dict | None] | None = None,
                 limits: ParseLimits | None = None) -> None:
        self.account_key = account_key
        self.lookup = lookup or (lambda _key: None)
        self.limits = limits or ParseLimits()
        if min(self.limits.depth, self.limits.nodes, self.limits.text_chars,
               self.limits.json_chars) < 1 or not 2 <= self.limits.parts <= 500:
            raise ValueError("invalid QQ parser limits")

    def parse(self, data: dict[str, Any]) -> ParsedMessage:
        """展开已下发的结构；缺失正文、坏结构及截断均输出可见提示。"""
        parts: list[dict[str, Any]] = []
        issues: list[str] = []
        urls: dict[str, int] = {}
        # 每条分支独立追踪祖先，合法的重复消息不能被全局去重吞掉。
        stack = [(data, "0", "current", (), frozenset())]
        visited = 0
        text_chars = 0
        attachment_count = 0

        def issue(reason: str, path: str) -> None:
            if len(issues) < 32:
                issues.append(f"{reason}@{path}")

        def append(part: dict[str, Any], path: str) -> None:
            nonlocal text_chars
            if len(parts) >= self.limits.parts - 1:
                issue("part_limit", path)
                return
            if part["type"] == "TEXT":
                remaining = self.limits.text_chars - text_chars
                value = part["text"]
                if len(value) > remaining:
                    value = value[:remaining]
                    issue("text_limit", path)
                text_chars += len(value)
                if not value:
                    return
                part = {**part, "text": value}
            parts.append(part)

        while stack:
            node, path, relation, ancestors, references = stack.pop()
            visited += 1
            if visited > self.limits.nodes or len(parts) >= self.limits.parts - 1:
                issue("node_limit" if visited > self.limits.nodes else "part_limit", path)
                break
            if path.count(".") > self.limits.depth:
                issue("depth_limit", path)
                continue
            if not isinstance(node, dict):
                issue("invalid_element", path)
                continue
            if any(node is parent for parent in ancestors):
                issue("cycle", path)
                continue
            # 保留祖先对象的强引用，避免临时解码对象被回收后重用对象编号。
            ancestor_nodes = (*ancestors, node)
            own, reference = message_indices(node)
            has_body = bool(node.get("content") or node.get("attachments")
                            or node.get("msg_elements") or node.get("payload"))
            if relation != "current" and own and not has_body:
                if own in references:
                    issue("reference_cycle", path)
                    continue
                resolved = self.lookup(own)
                if resolved is None:
                    issue("reference_unavailable", path)
                    continue
                stack.append((resolved, path, "quote_cached", ancestors, references | {own}))
                continue
            content = node.get("content", "")
            if not isinstance(content, str):
                issue("invalid_content", path)
                content = ""
            children = node.get("msg_elements", [])
            if not isinstance(children, list):
                issue("invalid_elements", path)
                children = []
            quoted = node.get("message_type") == 103 or bool(reference)
            if not children and quoted:
                # 官方引用可能仅下发 ext 索引，此时只查同会话的本地历史。
                if reference:
                    children = [{"msg_idx": reference}]
                else:
                    issue("quote_body_unavailable", path)
            if relation != "current":
                author = node.get("author")
                author = author if isinstance(author, dict) else {}
                metadata = {"sender": author.get("username") or author.get("user_openid")
                            or author.get("member_openid") or author.get("id"),
                            "timestamp": node.get("timestamp"), "messageIndex": own or None}
                label = json.dumps({k: v for k, v in metadata.items() if v is not None},
                                   ensure_ascii=True, separators=(",", ":"))
                append(text_part(f"[qq-message path={path} relation={relation} metadata={label}]"), path)
            if content.strip() or relation == "current":
                append(text_part(content.strip() or "[empty]"), path)
            attachments = node.get("attachments", [])
            if not isinstance(attachments, list):
                issue("invalid_attachments", path)
                attachments = []
            # 结构化附件优先，同一节点的摘要附件仅作为兼容来源。
            composite = [{"url": match["url"], "filename": match["filename"].strip(),
                          "size": match["size"]} for match in COMPOSITE_ATTACHMENT.finditer(content)]
            for attachment in (*attachments, *composite):
                if not isinstance(attachment, dict):
                    issue("invalid_attachment", path)
                    continue
                part = self._attachment(attachment, attachment_count + 1)
                if part is None:
                    issue("attachment_unavailable", path)
                    continue
                url = part["sourceUrl"]
                aliases = {url}
                for key in ("voice_wav_url", "download_url", "file_url", "url"):
                    alias = attachment.get(key)
                    if isinstance(alias, str) and alias.strip().startswith(("https://", "http://")):
                        aliases.add(alias.strip())
                existing = next((urls[alias] for alias in aliases if alias in urls), None)
                if existing is not None:
                    # 重复出现仍保留位置，但只创建一次附件下载。
                    if relation != "current":
                        append(text_part(f"[qq-attachment-ref index={existing}]"), path)
                    urls.update({alias: existing for alias in aliases})
                    continue
                before = len(parts)
                append(part, path)
                if len(parts) > before:
                    attachment_count += 1
                    urls.update({alias: attachment_count for alias in aliases})
                else:
                    break
                asr = attachment.get("asr_refer_text")
                if isinstance(asr, str) and asr.strip():
                    append(text_part(f"[qq-voice-transcript] {asr.strip()}"), path)
            # 只解码明确的 payload 包装，不把任意预览、头像或用户 JSON 当成正文。
            wrapped = node.get("payload")
            if wrapped is not None:
                if isinstance(wrapped, str):
                    if len(wrapped) > self.limits.json_chars:
                        issue("json_limit", path)
                        wrapped = None
                    else:
                        try:
                            wrapped = json.loads(wrapped)
                        except (ValueError, RecursionError):
                            issue("invalid_payload", path)
                            wrapped = None
                if isinstance(wrapped, dict):
                    children = [*children, wrapped]
                elif isinstance(wrapped, list):
                    children = [*children, *wrapped]
                elif wrapped is not None:
                    issue("invalid_payload", path)
            if relation != "current" and not has_body:
                issue("element_body_unavailable", path)
            # 限制待遍历队列宽度，避免巨大列表在检查节点预算前扩大内存。
            remaining = self.limits.nodes - visited
            if len(children) > remaining:
                issue("node_limit", path)
            for index in reversed(range(min(len(children), remaining))):
                stack.append((children[index], f"{path}.{index}",
                              "quote" if quoted and index == 0 else "element",
                              ancestor_nodes, references))

        # 正文与 TEXT 分段同步清理附件 URL，防止自动化重复提交同一个资源。
        def remove_attachment_url(match: re.Match) -> str:
            value = match[0]
            if value in urls:
                return ""
            trimmed = value.rstrip(".,;:!?)]}")
            return value[len(trimmed):] if trimmed in urls else value

        for part in parts:
            if part["type"] == "TEXT":
                part["text"] = HTTP_URL.sub(remove_attachment_url, part["text"]).strip() or "[empty]"
        if issues:
            parts.append(text_part("[qq-incomplete " + "; ".join(issues) + "]"))
        body = "\n".join(part["text"] for part in parts if part["type"] == "TEXT")
        return ParsedMessage(body or "[empty]", parts, tuple(issues))

    def _attachment(self, item: dict[str, Any], index: int) -> dict[str, Any] | None:
        """标准化官方媒体类型并保留完整下载地址，不能截断签名 URL。"""
        source = item.get("voice_wav_url") or item.get("download_url") \
            or item.get("file_url") or item.get("url")
        if not isinstance(source, str) or len(source) > 4096:
            return None
        source = source.strip()
        try:
            url = urlsplit(source)
        except ValueError:
            return None
        if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password:
            return None
        name = str(item.get("filename") or item.get("file_name")
                   or item.get("name") or f"qq-attachment-{index}")[:1024]
        mime = str(item.get("content_type") or item.get("mime") or "").lower()
        if mime == "voice" or item.get("voice_wav_url"):
            mime = "audio/wav" if item.get("voice_wav_url") else "audio/silk"
        elif mime in {"", "file"}:
            mime = mimetypes.guess_type(name)[0] or "application/octet-stream"
        kind = ("IMAGE" if mime.startswith("image/") else "VIDEO" if mime.startswith("video/")
                else "RECORD" if mime.startswith("audio/") else "FILE")
        return {"type": "ATTACHMENT", "text": None, "attachmentType": kind,
                "providerFileId": str(item.get("file_id") or item.get("id") or "")[:512] or None,
                "providerAccountKey": self.account_key, "sourceUrl": source,
                "fileName": name, "mimeType": mime[:255],
                "declaredSize": parse_size(item.get("size") or item.get("file_size"))}
