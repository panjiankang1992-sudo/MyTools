#!/usr/bin/env python3
"""Import one source-backed book and atomically publish it through Storage Gateway."""

from __future__ import annotations

import hashlib
import html
import http.client
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.parse
import urllib.request

MAX_CHAPTERS = 20_000
MAX_CHAPTER_BYTES = 10 * 1024 * 1024
MAX_OUTPUT_BYTES = 512 * 1024 * 1024
INVALID_FILENAME = re.compile(r"[\x00-\x1f\x7f/\\:*?\"<>|]")
BREAK_TAGS = re.compile(r"(?i)<\s*(br|/p|/div|/li|/h[1-6])[^>]*>")
HTML_TAGS = re.compile(r"<[^>]+>")
BLOCKED_CONTENT = re.compile(r"\u514d\u767b\u5f55\u8bbf\u95ee\u6b21\u6570\u5df2\u8fbe\u4e0a\u9650|"
                             r"\u8bf7\u767b\u5f55\u540e\u5237\u65b0\u9875\u9762|"
                             r"\u8bbf\u95ee\u8fc7\u4e8e\u9891\u7e41|\u8bf7\u8f93\u5165\u9a8c\u8bc1\u7801|Access Denied",
                             re.IGNORECASE)


class ReaderRuntimeClient:
    """Execution-isolated Reader Runtime client."""

    def __init__(self, base_url: str, secure_key: str, namespace: str):
        if not secure_key:
            raise ValueError("Reader Runtime secure key is missing")
        self._base_url = base_url.rstrip("/")
        self._headers = {"Accept": "application/json", "X-Secure-Key": secure_key, "X-User-NS": namespace}

    def install_source(self, snapshot: dict) -> None:
        """Install only the immutable source snapshot used by this import."""
        self._request("/reader3/deleteAllBookSources", "POST", b"")
        self._request("/reader3/saveBookSources", "POST",
                      json.dumps([snapshot], ensure_ascii=False).encode("utf-8"))

    def catalog(self, source_url: str, book_url: str) -> tuple[dict, list[dict]]:
        """Load book metadata and its complete chapter list."""
        source = urllib.parse.urlencode({"bookSourceUrl": source_url})
        info = self._request("/reader3/getBookInfo?" + urllib.parse.urlencode({"url": book_url}) + "&" + source,
                             "GET", None).get("data")
        chapters = self._request("/reader3/getChapterList?" +
                                 urllib.parse.urlencode({"bookUrl": book_url}) + "&" + source,
                                 "GET", None).get("data")
        if not isinstance(info, dict):
            info = {}
        if not isinstance(chapters, list) or not chapters or len(chapters) > MAX_CHAPTERS:
            raise ValueError("chapter catalog is empty or exceeds limit")
        return info, chapters

    def content(self, source_url: str, chapter_url: str) -> str:
        """Load one chapter as text."""
        query = urllib.parse.urlencode({"chapterUrl": chapter_url, "bookSourceUrl": source_url})
        data = self._request("/reader3/getBookContent?" + query, "GET", None).get("data")
        if not isinstance(data, str):
            raise ValueError("chapter content is empty")
        content = plain_text(data)
        if not content or BLOCKED_CONTENT.search(content):
            raise ValueError("chapter content is empty or blocked")
        if len(content.encode("utf-8")) > MAX_CHAPTER_BYTES:
            raise ValueError("chapter content exceeds limit")
        return content

    def _request(self, path: str, method: str, body: bytes | None) -> dict:
        headers = dict(self._headers)
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self._base_url + path, data=body, headers=headers, method=method)
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if not isinstance(payload, dict) or not payload.get("isSuccess"):
            raise RuntimeError("Reader Runtime rejected request")
        return payload


class StorageGatewayClient:
    """Storage Gateway streaming upload client."""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Storage Gateway token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def publish(self, path: Path, root_name: str, relative_path: str,
                idempotency_key: str, size: int, sha256: str) -> str:
        """Create an idempotent upload and stream the artifact body."""
        body = json.dumps({"rootName": root_name, "relativePath": relative_path,
                           "expectedSize": size, "expectedSha256": sha256,
                           "idempotencyKey": idempotency_key}, separators=(",", ":")).encode()
        request = urllib.request.Request(f"{self._base_url}/api/internal/v1/storage/uploads", data=body,
                                         method="POST", headers=self._headers("application/json"))
        with urllib.request.urlopen(request, timeout=30) as response:
            upload = json.loads(response.read().decode("utf-8"))
        if upload.get("status") != "SUCCEEDED":
            self._stream(upload["id"], path, size)
        status_request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/storage/uploads/{upload['id']}",
            headers=self._headers(None))
        with urllib.request.urlopen(status_request, timeout=30) as response:
            completed = json.loads(response.read().decode("utf-8"))
        if completed.get("status") != "SUCCEEDED" or not completed.get("storageUri"):
            raise RuntimeError("Storage Gateway did not publish artifact")
        return str(completed["storageUri"])

    def _stream(self, upload_id: str, path: Path, size: int) -> None:
        parsed = urllib.parse.urlsplit(self._base_url)
        connection_type = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
        connection = connection_type(parsed.hostname, parsed.port, timeout=60)
        target = (parsed.path.rstrip("/") + f"/api/internal/v1/storage/uploads/{upload_id}/content")
        connection.putrequest("PUT", target)
        connection.putheader("Authorization", f"Bearer {self._token}")
        connection.putheader("Content-Type", "application/octet-stream")
        connection.putheader("Content-Length", str(size))
        connection.endheaders()
        with path.open("rb") as source:
            while chunk := source.read(64 * 1024):
                connection.send(chunk)
        response = connection.getresponse()
        response.read()
        connection.close()
        if response.status < 200 or response.status >= 300:
            raise RuntimeError(f"Storage Gateway upload failed: {response.status}")

    def _headers(self, content_type: str | None) -> dict[str, str]:
        headers = {"Authorization": f"Bearer {self._token}", "Accept": "application/json"}
        if content_type:
            headers["Content-Type"] = content_type
        return headers


def safe_title(value: object) -> str:
    """Create a bounded display and file title."""
    normalized = INVALID_FILENAME.sub("_", str(value or "").strip())
    normalized = " ".join(normalized.split()) or "Imported book"
    return normalized[:180]


def plain_text(value: str) -> str:
    """Normalize Runtime HTML content to stable reader text."""
    with_lines = BREAK_TAGS.sub("\n", value)
    without_tags = HTML_TAGS.sub("", with_lines)
    decoded = html.unescape(without_tags).replace("\u00a0", " ").replace("\r\n", "\n").replace("\r", "\n")
    return re.sub(r"\n{3,}", "\n\n", decoded).strip()


def write_book(path: Path, runtime: ReaderRuntimeClient, parameters: dict) -> tuple[str, str, int, int, str]:
    """Stream the full source book into one bounded UTF-8 text artifact."""
    runtime.install_source(parameters["sourceSnapshot"])
    info, chapters = runtime.catalog(str(parameters["sourceUrl"]), str(parameters["bookUrl"]))
    title = safe_title(info.get("name") or parameters.get("title"))
    author = str(info.get("author") or parameters.get("author") or "").strip()[:200]
    size = 0
    digest = hashlib.sha256()

    def append(handle, value: str) -> None:
        nonlocal size
        data = value.encode("utf-8")
        size += len(data)
        if size > MAX_OUTPUT_BYTES:
            raise ValueError("ebook output exceeds limit")
        handle.write(data)
        digest.update(data)

    with path.open("wb") as output:
        append(output, title + "\n")
        if author:
            append(output, "Author: " + author + "\n")
        append(output, "\n")
        for chapter in chapters:
            chapter_title = str(chapter.get("title") or "Chapter").strip()[:500]
            chapter_url = str(chapter.get("url") or "").strip()
            if not chapter_url:
                raise ValueError("chapter URL is missing")
            content = runtime.content(str(parameters["sourceUrl"]), chapter_url)
            append(output, chapter_title + "\n\n" + content + "\n\n")
    return title, author, len(chapters), size, digest.hexdigest()


def execute(parameters: dict, runtime: ReaderRuntimeClient, storage: StorageGatewayClient,
            work_directory: Path) -> dict:
    """Generate and publish one complete source-backed ebook."""
    request_id = str(parameters["requestId"])
    artifact = work_directory / "ebook.txt"
    title, author, chapter_count, size, sha256 = write_book(artifact, runtime, parameters)
    relative_path = f"ebooks/imports/{request_id}/{safe_title(title)}.txt"
    storage_uri = storage.publish(artifact, str(parameters["storageRoot"]), relative_path,
                                  f"reader-import:{request_id}:v1", size, sha256)
    return {"requestId": request_id, "sourceId": str(parameters["sourceId"]), "title": title,
            "author": author, "chapterCount": chapter_count, "size": size,
            "sha256": sha256, "storageUri": storage_uri}


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one ebook import task."""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    parameters = context["parameters"]
    namespace = f"{parameters['ownerId']}:import:{context['executionId']}"
    runtime = ReaderRuntimeClient(os.getenv("READER_RUNTIME_BASE_URL", "http://127.0.0.1:23120"),
                                  os.environ.get("READER_RUNTIME_SECURE_KEY", ""), namespace)
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.environ.get("STORAGE_INTERNAL_TOKEN", ""))
    write_result(execute(parameters, runtime, storage, context_path.parent))


if __name__ == "__main__":
    main()
