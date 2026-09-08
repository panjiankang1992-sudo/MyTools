#!/usr/bin/env python3
"""Import one owner-verified TXT or EPUB media item into managed Reader storage."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.parse
import urllib.request
import zipfile

from mytools_task_sdk.ebook import epub_spine_resources, validate_archive
from mytools_task_sdk.storage import StorageGatewayClient

MAX_INPUT_BYTES = 512 * 1024 * 1024
MAX_EPUB_ENTRIES = 10_000
MAX_EPUB_EXPANDED_BYTES = 512 * 1024 * 1024
CHAPTER_PATTERN = re.compile(
    r"(?i)^\s*(?:\u7b2c[0-9\u96f6\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d"
    r"\u5341\u767e\u5343\u4e07\u4e24]+[\u7ae0\u8282\u5377\u56de]|chapter\s+\d+).*$")
INVALID_FILENAME = re.compile(r"[\x00-\x1f\x7f/\\:*?\"<>|]")
MIME_FORMATS = {"text/plain": ("TXT", ".txt"), "application/epub+zip": ("EPUB", ".epub")}


def safe_title(value: object) -> str:
    """Return a stable file-name component without using source paths."""
    normalized = INVALID_FILENAME.sub("_", str(value or "").strip())
    normalized = " ".join(normalized.split()) or "Managed book"
    return normalized[:180]


def supported_mime_type(parameters: dict) -> str:
    """Return the normalized managed ebook MIME type or reject unsupported media."""
    mime_type = str(parameters.get("mimeType", "")).lower()
    if mime_type not in MIME_FORMATS:
        raise ValueError("Managed ebook import requires text/plain or application/epub+zip")
    return mime_type


def epub_chapter_count(target: Path) -> int:
    """Validate a bounded EPUB archive and return its immutable spine entry count."""
    with zipfile.ZipFile(target) as archive:
        validate_archive(archive, MAX_EPUB_ENTRIES, MAX_EPUB_EXPANDED_BYTES)
        resources = epub_spine_resources(archive)
    if len(resources) > MAX_EPUB_ENTRIES:
        raise ValueError("EPUB spine exceeds limit")
    return len(resources)


def chapter_count(target: Path, mime_type: str) -> int:
    """Return a deterministic chapter count after validating the frozen file format."""
    if mime_type == "application/epub+zip":
        return epub_chapter_count(target)
    content = target.read_text(encoding="utf-8")
    if not content.strip():
        raise ValueError("Managed ebook text is empty")
    return max(1, sum(1 for line in content.splitlines() if CHAPTER_PATTERN.match(line)))


def download_ebook(parameters: dict, target: Path, media_url: str, media_token: str) -> tuple[int, str, int, str]:
    """Download a bounded item, validate its frozen identity, and validate its declared format."""
    if not media_token:
        raise ValueError("Media Library internal token is missing")
    mime_type = supported_mime_type(parameters)
    owner_id = int(parameters["ownerId"])
    media_id = urllib.parse.quote(str(parameters["mediaItemId"]), safe="")
    url = media_url.rstrip("/") + "/internal/v1/media/items/" + media_id + "/content?" + \
          urllib.parse.urlencode({"ownerId": owner_id, "thumbnail": "false"})
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + media_token,
                                                    "Accept-Encoding": "identity"})
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(request, timeout=60) as response, target.open("wb") as output:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if size > MAX_INPUT_BYTES:
                raise ValueError("Managed ebook exceeds task limit")
            digest.update(chunk)
            output.write(chunk)
    expected_size = int(parameters["sizeBytes"])
    expected_hash = str(parameters["contentSha256"]).lower()
    actual_hash = digest.hexdigest()
    if size != expected_size or actual_hash != expected_hash:
        raise ValueError("Managed ebook content no longer matches frozen media identity")
    chapters = chapter_count(target, mime_type)
    return size, actual_hash, chapters, mime_type


def execute(parameters: dict, storage: StorageGatewayClient, work_directory: Path,
            media_url: str, media_token: str) -> dict:
    """Copy one frozen media item to Reader storage and return the shared import result shape."""
    request_id = str(parameters["requestId"])
    mime_type = supported_mime_type(parameters)
    format_name, extension = MIME_FORMATS[mime_type]
    artifact = work_directory / ("ebook" + extension)
    size, digest, chapters, _ = download_ebook(parameters, artifact, media_url, media_token)
    title = safe_title(parameters["title"])
    relative_path = f"ebooks/managed-media/{request_id}/{title}{extension}"
    storage_uri = storage.publish(artifact, str(parameters["storageRoot"]), relative_path,
                                  f"reader-managed-import:{request_id}:v1", size, digest)
    return {"requestId": request_id, "sourceId": str(parameters["sourceId"]), "title": title,
            "author": "", "format": format_name, "chapterCount": chapters, "size": size, "sha256": digest,
            "storageUri": storage_uri}


def write_result(result: dict) -> None:
    """Write a bounded task result atomically."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one managed Media Library ebook import."""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.environ.get("STORAGE_INTERNAL_TOKEN", ""))
    write_result(execute(context["parameters"], storage, context_path.parent,
                         os.getenv("MEDIA_LIBRARY_URL", "http://127.0.0.1:23300"),
                         os.environ.get("MEDIA_LIBRARY_INTERNAL_TOKEN", "")))


if __name__ == "__main__":
    main()
