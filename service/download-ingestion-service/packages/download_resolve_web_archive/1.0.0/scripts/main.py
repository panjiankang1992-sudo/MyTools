#!/usr/bin/env python3
"""安全解析网页正文和嵌入媒体并创建原子下载子任务。"""

from __future__ import annotations

from dataclasses import dataclass
from html.parser import HTMLParser
import hashlib
import ipaddress
import json
import mimetypes
import os
from pathlib import Path
import re
import socket
import tempfile
from urllib.error import HTTPError
from urllib.parse import unquote, urljoin, urlparse
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from uuid import UUID

from mytools_task_sdk.context import TaskContext
from mytools_task_sdk.orchestration import wait_all_or_cancel

MAX_PAGE_BYTES = 8 * 1024 * 1024
MAX_TEXT_BYTES = 2 * 1024 * 1024
REDIRECT_CODES = {301, 302, 303, 307, 308}
BLOCK_TAGS = {"article", "blockquote", "br", "div", "figcaption", "footer", "h1", "h2",
              "h3", "header", "li", "main", "p", "pre", "section", "td", "tr"}
SKIP_TAGS = {"script", "style", "noscript", "svg", "template"}


@dataclass(frozen=True, slots=True)
class Page:
    """表示网页解析后的有界正文和资源清单。"""

    title: str
    text: str
    media_urls: list[str]


class NoRedirect(HTTPRedirectHandler):
    """禁止 urllib 自动跳转，以便逐跳重新执行 SSRF 校验。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """返回空值让调用方显式处理 Location。"""
        return None


def validated_url(value: object, resolver=socket.getaddrinfo) -> str:
    """只允许所有解析地址均为公网的绝对 HTTP(S) URL。"""
    url = str(value or "").strip()
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("url must be absolute public HTTP or HTTPS")
    try:
        addresses = resolver(parsed.hostname, parsed.port or (443 if parsed.scheme == "https" else 80),
                             type=socket.SOCK_STREAM)
    except OSError as exception:
        raise ValueError("url host cannot be resolved") from exception
    if not addresses:
        raise ValueError("url host cannot be resolved")
    if any(not ipaddress.ip_address(value[4][0]).is_global for value in addresses):
        raise ValueError("url host resolves to a non-public address")
    return url


class ReadableParser(HTMLParser):
    """提取正文、标题和媒体 URL，忽略脚本及样式内容。"""

    def __init__(self, base_url: str):
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.title_parts = []
        self.body_parts = []
        self.article_parts = []
        self.media_urls = []
        self.title_depth = self.body_depth = self.article_depth = self.skip_depth = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        tag = tag.lower()
        if self.skip_depth:
            if tag in SKIP_TAGS:
                self.skip_depth += 1
            return
        if tag in SKIP_TAGS:
            self.skip_depth = 1
            return
        values = {str(key).lower(): str(value or "") for key, value in attrs}
        if tag == "title":
            self.title_depth += 1
        if tag == "body":
            self.body_depth += 1
        if tag == "article":
            self.article_depth += 1
        if tag in BLOCK_TAGS:
            self._separator()
        if tag in {"img", "video", "audio", "source"}:
            candidate = values.get("data-src") or values.get("src") or values.get("poster") or ""
            if candidate and not candidate.startswith(("data:", "blob:")):
                self.media_urls.append(urljoin(self.base_url, candidate))

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if self.skip_depth:
            if tag in SKIP_TAGS:
                self.skip_depth -= 1
            return
        if tag in BLOCK_TAGS:
            self._separator()
        if tag == "title" and self.title_depth:
            self.title_depth -= 1
        if tag == "article" and self.article_depth:
            self.article_depth -= 1
        if tag == "body" and self.body_depth:
            self.body_depth -= 1

    def handle_data(self, data: str) -> None:
        if self.skip_depth:
            return
        value = re.sub(r"\s+", " ", data).strip()
        if not value:
            return
        if self.title_depth:
            self.title_parts.append(value + " ")
        if self.body_depth:
            self.body_parts.append(value + " ")
        if self.article_depth:
            self.article_parts.append(value + " ")

    def _separator(self) -> None:
        for values, enabled in ((self.body_parts, self.body_depth),
                                (self.article_parts, self.article_depth)):
            if enabled and values and values[-1] != "\n":
                values.append("\n")


def clean_text(parts: list[str]) -> str:
    """将解析片段规范化为稳定文本。"""
    value = "".join(parts)
    value = re.sub(r"[ \t]+", " ", value)
    value = re.sub(r" *\n *", "\n", value)
    return re.sub(r"\n{3,}", "\n\n", value).strip()


def parse_page(html: str, base_url: str) -> Page:
    """解析一个已受大小限制的 HTML 文档。"""
    parser = ReadableParser(base_url)
    parser.feed(html)
    parser.close()
    title = clean_text(parser.title_parts) or (urlparse(base_url).hostname or "web-page")
    text = clean_text(parser.article_parts) or clean_text(parser.body_parts)
    media = []
    seen = set()
    for value in parser.media_urls:
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or value in seen:
            continue
        seen.add(value)
        media.append(value)
    return Page(title[:300], text, media)


def fetch(url: str, maximum_bytes: int, opener, resolver=socket.getaddrinfo):
    """逐跳校验并获取 HTML；非 HTML 只返回最终资源描述。"""
    current = url
    for _ in range(6):
        validated_url(current, resolver)
        request = Request(current, headers={"User-Agent": "MyTools-WebArchive-Resolver/1.0"})
        try:
            response = opener(request, timeout=30)
        except HTTPError as exception:
            if exception.code not in REDIRECT_CODES:
                raise RuntimeError(f"web page request failed with HTTP {exception.code}") from exception
            response = exception
        with response:
            status = getattr(response, "status", getattr(response, "code", 200))
            if status in REDIRECT_CODES:
                location = response.headers.get("Location")
                if not location:
                    raise ValueError("web redirect is missing Location")
                current = urljoin(current, location)
                continue
            if status >= 400:
                raise RuntimeError(f"web page request failed with HTTP {status}")
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].lower()
            if content_type not in {"text/html", "application/xhtml+xml"}:
                return current, None, content_type or "application/octet-stream"
            declared = response.headers.get("Content-Length")
            if declared and int(declared) > maximum_bytes:
                raise ValueError("web page declared size exceeds limit")
            data = response.read(maximum_bytes + 1)
            if len(data) > maximum_bytes:
                raise ValueError("web page exceeds size limit")
            charset = response.headers.get_content_charset() or "utf-8"
            return current, data.decode(charset, errors="replace"), content_type
    raise ValueError("web page has too many redirects")


def safe_name(value: str, fallback: str) -> str:
    """生成不含路径分隔符的有界文件名。"""
    name = Path(unquote(value)).name.strip().replace("\x00", "")
    name = re.sub(r"[/\\]+", "_", name)[:200]
    return name or fallback


def build_resources(parameters: dict, final_url: str, html: str | None,
                    content_type: str) -> list[dict]:
    """将直接资源或网页正文和媒体转换为子任务规格。"""
    if html is None:
        suffix = mimetypes.guess_extension(content_type) or ""
        return [{"kind": "HTTP", "url": final_url,
                 "fileName": safe_name(urlparse(final_url).path, "web-direct" + suffix),
                 "mimeType": content_type}]
    page = parse_page(html, final_url)
    maximum_assets = int(parameters.get("maxAssets", 50))
    if maximum_assets < 0 or maximum_assets > 100 or len(page.media_urls) > maximum_assets:
        raise ValueError("web media count exceeds limit")
    resources = []
    minimum_text = int(parameters.get("minTextBytes", 64))
    maximum_text = int(parameters.get("maxTextBytes", MAX_TEXT_BYTES))
    if minimum_text < 0 or maximum_text < 1 or maximum_text > MAX_TEXT_BYTES:
        raise ValueError("web text limits are invalid")
    body = f"Title: {page.title}\nSource: {final_url}\n\n{page.text}\n"
    encoded = body.encode()
    if len(encoded) >= minimum_text:
        if len(encoded) > maximum_text:
            body = encoded[:maximum_text].decode("utf-8", errors="ignore")
        resources.append({"kind": "TEXT", "content": body,
                          "fileName": safe_name(page.title, "web-page") + ".txt",
                          "mimeType": "text/plain"})
    for index, media_url in enumerate(page.media_urls, start=1):
        name = safe_name(urlparse(media_url).path, f"web-media-{index}")
        resources.append({"kind": "HTTP", "url": media_url, "fileName": name,
                          "mimeType": mimetypes.guess_type(name)[0]
                          or "application/octet-stream"})
    if not resources:
        raise ValueError("web page produced no archive resources")
    return resources


def execute(context: TaskContext, parameters: dict, final_url: str,
            resources: list[dict]) -> dict:
    """创建正文或媒体子任务并等待全部成功。"""
    request_id = str(parameters["downloadRequestId"])
    UUID(request_id)
    children = []
    for index, resource in enumerate(resources, start=1):
        fingerprint_source = resource.get("url") or resource.get("content") or ""
        fingerprint = hashlib.sha256(str(fingerprint_source).encode()).hexdigest()
        item_id = f"web:{index}:{fingerprint}"
        common = {"downloadRequestId": request_id, "itemId": item_id,
                  "fileName": resource["fileName"],
                  "ownerId": int(parameters.get("ownerId") or 0),
                  "assetMimeType": resource["mimeType"],
                  "assetSourceBusinessId": f"{request_id}:{item_id}"}
        if resource["kind"] == "TEXT":
            task_name = "download_publish_text"
            child_parameters = {**common, "content": resource["content"]}
        else:
            task_name = "download_http_asset"
            child_parameters = {**common, "url": resource["url"],
                                "maxBytes": int(parameters.get(
                                    "maxBytesPerItem", 2 * 1024 * 1024 * 1024))}
        child = context.create_child(
            task_name, child_parameters, f"web-resource:{request_id}:{fingerprint}",
            business_type="DOWNLOAD_REQUEST", business_id=request_id)
        children.append(child)
    wait_all_or_cancel(context, children, 1500)
    return {"requestId": request_id, "finalUrl": final_url,
            "resourceCount": len(children), "childTaskIds": [child.id for child in children]}


def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行一个网页归档父任务。"""
    context = TaskContext.load()
    parameters = context.parameters
    maximum = int(parameters.get("maxPageBytes", 4 * 1024 * 1024))
    if maximum < 1 or maximum > MAX_PAGE_BYTES:
        raise ValueError("maxPageBytes is outside the supported range")
    proxy = os.getenv("WEB_ARCHIVE_PROXY_URL", "")
    handlers = [NoRedirect()]
    if proxy:
        handlers.append(ProxyHandler({"http": proxy, "https": proxy}))
    opener = build_opener(*handlers).open
    final_url, html, content_type = fetch(str(parameters["url"]), maximum, opener)
    resources = build_resources(parameters, final_url, html, content_type)
    write_result(execute(context, parameters, final_url, resources))


if __name__ == "__main__":
    main()
