"""Execution-isolated client for the legacy Reader Runtime boundary."""

from __future__ import annotations

import html
import json
import re
import urllib.parse
import urllib.request

BREAK_TAGS = re.compile(r"(?i)<\s*(br|/p|/div|/li|/h[1-6])[^>]*>")
HTML_TAGS = re.compile(r"<[^>]+>")
BLOCKED_CONTENT = re.compile(r"\u514d\u767b\u5f55\u8bbf\u95ee\u6b21\u6570\u5df2\u8fbe\u4e0a\u9650|"
                             r"\u8bf7\u767b\u5f55\u540e\u5237\u65b0\u9875\u9762|"
                             r"\u8bbf\u95ee\u8fc7\u4e8e\u9891\u7e41|\u8bf7\u8f93\u5165\u9a8c\u8bc1\u7801|Access Denied",
                             re.IGNORECASE)


def plain_text(value: str) -> str:
    """Normalize Runtime HTML content to stable reader text."""
    with_lines = BREAK_TAGS.sub("\n", value)
    without_tags = HTML_TAGS.sub("", with_lines)
    decoded = html.unescape(without_tags).replace("\u00a0", " ").replace("\r\n", "\n").replace("\r", "\n")
    return re.sub(r"\n{3,}", "\n\n", decoded).strip()


class ReaderRuntimeClient:
    """Bounded client that isolates each task in its own Runtime namespace."""

    def __init__(self, base_url: str, secure_key: str, namespace: str,
                 timeout_seconds: int = 30, max_chapters: int = 20_000,
                 max_chapter_bytes: int = 10 * 1024 * 1024):
        if not secure_key:
            raise ValueError("Reader Runtime secure key is missing")
        self._base_url = base_url.rstrip("/")
        self._headers = {"Accept": "application/json", "X-Secure-Key": secure_key, "X-User-NS": namespace}
        self._timeout_seconds = timeout_seconds
        self._max_chapters = max_chapters
        self._max_chapter_bytes = max_chapter_bytes

    def install_source(self, snapshot: dict) -> None:
        """Install only one immutable source snapshot."""
        self._request("/reader3/deleteAllBookSources", "POST", b"")
        self._request("/reader3/saveBookSources", "POST",
                      json.dumps([snapshot], ensure_ascii=False).encode("utf-8"))

    def catalog(self, source_url: str, book_url: str) -> tuple[dict, list[dict]]:
        """Load bounded book metadata and chapter catalog."""
        source = urllib.parse.urlencode({"bookSourceUrl": source_url})
        info = self._request("/reader3/getBookInfo?" + urllib.parse.urlencode({"url": book_url}) + "&" + source,
                             "GET", None).get("data")
        chapters = self._request("/reader3/getChapterList?" +
                                 urllib.parse.urlencode({"bookUrl": book_url}) + "&" + source,
                                 "GET", None).get("data")
        if not isinstance(info, dict):
            info = {}
        if not isinstance(chapters, list) or not chapters or len(chapters) > self._max_chapters:
            raise ValueError("chapter catalog is empty or exceeds limit")
        return info, chapters

    def content(self, source_url: str, chapter_url: str) -> str:
        """Load and normalize one bounded chapter."""
        query = urllib.parse.urlencode({"chapterUrl": chapter_url, "bookSourceUrl": source_url})
        data = self._request("/reader3/getBookContent?" + query, "GET", None).get("data")
        if not isinstance(data, str):
            raise ValueError("chapter content is empty")
        content = plain_text(data)
        if not content or BLOCKED_CONTENT.search(content):
            raise ValueError("chapter content is empty or blocked")
        if len(content.encode("utf-8")) > self._max_chapter_bytes:
            raise ValueError("chapter content exceeds limit")
        return content

    def _request(self, path: str, method: str, body: bytes | None) -> dict:
        headers = dict(self._headers)
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self._base_url + path, data=body, headers=headers, method=method)
        with urllib.request.urlopen(request, timeout=self._timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if not isinstance(payload, dict) or not payload.get("isSuccess"):
            raise RuntimeError("Reader Runtime rejected request")
        return payload
