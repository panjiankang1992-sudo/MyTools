#!/usr/bin/env python3
"""Freeze normalized chapter text for one managed audiobook generation."""

from __future__ import annotations

import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.request
import zipfile

from mytools_task_sdk.ebook import decode_text, epub_spine_resources, read_zip_entry, validate_archive
from mytools_task_sdk.storage import StorageGatewayClient

MAX_INPUT_BYTES = 512 * 1024 * 1024
MAX_CHAPTER_BYTES = 10 * 1024 * 1024
MAX_CHAPTERS = 20_000
MAX_EPUB_ENTRIES = 10_000
MAX_EPUB_EXPANDED_BYTES = 512 * 1024 * 1024
MAX_EPUB_XHTML_BYTES = 4 * 1024 * 1024
BATCH_SIZE = 100
CONTROL_CHARACTERS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
EXCESSIVE_BLANK_LINES = re.compile(r"\n{3,}")
BLOCK_ELEMENTS = frozenset({"address", "article", "aside", "blockquote", "br", "caption", "dd", "div",
                            "dl", "dt", "figcaption", "figure", "footer", "h1", "h2", "h3", "h4",
                            "h5", "h6", "header", "hr", "li", "main", "ol", "p", "pre", "section",
                            "table", "tbody", "td", "th", "thead", "tr", "ul"})
SUPPRESSED_ELEMENTS = frozenset({"head", "math", "script", "style", "svg", "title"})


class ReaderServiceClient:
    """Authenticated client for the audiobook text projection internal contract."""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Reader Service internal token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def input(self, generation_id: str) -> dict:
        """Read the frozen source location and chapter locators."""
        request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/audiobook-generations/{generation_id}/text-projection-input",
            headers=self._headers())
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))

    def save(self, generation_id: str, chapters: list[dict]) -> None:
        """Persist one bounded group of published chapter text snapshots."""
        body = json.dumps({"chapters": chapters}, ensure_ascii=False,
                          separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/audiobook-generations/{generation_id}/text-projection-chapters",
            data=body, method="POST", headers=self._headers("application/json"))
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()

    def complete(self, generation_id: str) -> dict:
        """Advance a fully persisted generation to the text-ready state."""
        request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/audiobook-generations/{generation_id}/complete-text-projection",
            data=b"", method="POST", headers=self._headers("application/json"))
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))

    def _headers(self, content_type: str | None = None) -> dict[str, str]:
        headers = {"Authorization": f"Bearer {self._token}", "Accept": "application/json"}
        if content_type:
            headers["Content-Type"] = content_type
        return headers


def normalize_text(value: bytes) -> bytes:
    """Create a stable UTF-8 text representation without changing paragraph semantics."""
    decoded = value.decode("utf-8", errors="replace").replace("\r\n", "\n").replace("\r", "\n")
    decoded = CONTROL_CHARACTERS.sub("", decoded).replace("\ufeff", "")
    normalized = EXCESSIVE_BLANK_LINES.sub("\n\n", decoded).strip()
    if not normalized:
        raise ValueError("Chapter text is empty after normalization")
    return (normalized + "\n").encode("utf-8")


def chapter_bytes(source: bytes, chapter: dict) -> bytes:
    """Return one bounded byte slice from a TXT catalog entry."""
    resource_ref = str(chapter.get("resourceRef") or "")
    start = chapter.get("startOffset")
    end = chapter.get("endOffset")
    if not resource_ref.startswith("text:") or start is None or end is None:
        raise ValueError("Audiobook text projection currently requires TXT catalog offsets")
    if not isinstance(start, int) or not isinstance(end, int) or start < 0 or end <= start or end > len(source):
        raise ValueError("Chapter byte offsets are invalid")
    payload = source[start:end]
    if len(payload) > MAX_CHAPTER_BYTES:
        raise ValueError("Chapter text exceeds task limit")
    return payload


class VisibleTextParser(HTMLParser):
    """Extract visible EPUB document text without executing or preserving markup."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self._chunks: list[str] = []
        self._suppressed_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        """Record block boundaries and suppress non-readable document regions."""
        normalized = tag.lower()
        if normalized in SUPPRESSED_ELEMENTS:
            self._suppressed_depth += 1
        if self._suppressed_depth == 0 and normalized in BLOCK_ELEMENTS:
            self._chunks.append("\n")

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        """Record a visible boundary for a self-closing block element."""
        if self._suppressed_depth == 0 and tag.lower() in BLOCK_ELEMENTS:
            self._chunks.append("\n")

    def handle_endtag(self, tag: str) -> None:
        """Close suppressed regions and retain block-level paragraph boundaries."""
        normalized = tag.lower()
        if normalized in SUPPRESSED_ELEMENTS and self._suppressed_depth > 0:
            self._suppressed_depth -= 1
            return
        if self._suppressed_depth == 0 and normalized in BLOCK_ELEMENTS:
            self._chunks.append("\n")

    def handle_data(self, data: str) -> None:
        """Keep only readable text outside script, style, and document-head regions."""
        if self._suppressed_depth == 0:
            self._chunks.append(data)

    def text(self) -> str:
        """Return the visible text in document order."""
        return "".join(self._chunks)


def epub_chapter_bytes(archive: zipfile.ZipFile, chapter: dict, spine_resources: set[str]) -> bytes:
    """Extract one frozen EPUB spine XHTML entry as bounded visible UTF-8 text."""
    resource_ref = str(chapter.get("resourceRef") or "")
    if not resource_ref.startswith("epub:") or chapter.get("startOffset") is not None \
            or chapter.get("endOffset") is not None:
        raise ValueError("Audiobook EPUB chapter locator is invalid")
    resource = resource_ref[len("epub:"):]
    if resource not in spine_resources:
        raise ValueError("Audiobook EPUB chapter is not in the frozen spine")
    parser = VisibleTextParser()
    parser.feed(decode_text(read_zip_entry(archive, resource, MAX_EPUB_XHTML_BYTES)))
    parser.close()
    payload = parser.text().encode("utf-8")
    if len(payload) > MAX_CHAPTER_BYTES:
        raise ValueError("Chapter text exceeds task limit")
    return payload


def source_sha256(path: Path) -> str:
    """Return the streamed SHA-256 digest of a downloaded managed ebook artifact."""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def publish_chapter(storage: StorageGatewayClient, work_directory: Path, storage_root: str,
                    generation_id: str, chapter: dict, payload: bytes) -> dict:
    """Atomically publish one normalized chapter text snapshot."""
    index = int(chapter["index"])
    digest = hashlib.sha256(payload).hexdigest()
    character_count = len(payload.decode("utf-8").strip())
    if character_count == 0:
        raise ValueError("Chapter text is empty after normalization")
    path = work_directory / f"chapter-{index:05d}.txt"
    path.write_bytes(payload)
    storage_uri = storage.publish(path, storage_root,
                                  f"audiobooks/{generation_id}/text/{index:05d}-{digest}.txt",
                                  f"audiobook-text:{generation_id}:{index}:{digest}", len(payload), digest)
    return {"index": index, "contentSha256": digest, "storageUri": storage_uri, "sizeBytes": len(payload),
            "characterCount": character_count}


def execute(parameters: dict, storage: StorageGatewayClient, reader: ReaderServiceClient,
            work_directory: Path) -> dict:
    """Download, split, normalize, publish, and freeze every chapter in one generation."""
    generation_id = str(parameters["generationId"])
    storage_root = str(parameters["storageRoot"])
    input_data = reader.input(generation_id)
    chapters = input_data.get("chapters")
    if not isinstance(chapters, list) or not chapters or len(chapters) > MAX_CHAPTERS:
        raise ValueError("Audiobook generation chapter plan is invalid")
    source_path = work_directory / "source-book"
    storage.download(str(input_data["sourceStorageUri"]), source_path, MAX_INPUT_BYTES)
    expected_hash = str(input_data["expectedBookSha256"])
    if source_sha256(source_path) != expected_hash:
        raise ValueError("Managed ebook checksum does not match frozen generation")
    resource_prefixes = {str(chapter.get("resourceRef") or "").partition(":")[0] for chapter in chapters}
    if resource_prefixes == {"text"}:
        source = source_path.read_bytes()
        chapter_payload = lambda chapter: chapter_bytes(source, chapter)
        project_chapters(chapters, chapter_payload, storage, work_directory, storage_root, generation_id, reader)
    elif resource_prefixes == {"epub"}:
        archive = zipfile.ZipFile(source_path)
        try:
            validate_archive(archive, MAX_EPUB_ENTRIES, MAX_EPUB_EXPANDED_BYTES)
            spine_resources = set(epub_spine_resources(archive))
            chapter_payload = lambda chapter: epub_chapter_bytes(archive, chapter, spine_resources)
            project_chapters(chapters, chapter_payload, storage, work_directory, storage_root, generation_id, reader)
        finally:
            archive.close()
    else:
        raise ValueError("Audiobook generation contains unsupported mixed chapter formats")
    result = reader.complete(generation_id)
    return {"generationId": generation_id, "projectedChapterCount": int(result["projectedChapterCount"]),
            "projectedCharacterCount": int(result["projectedCharacterCount"])}


def project_chapters(chapters: list[dict], chapter_payload, storage: StorageGatewayClient, work_directory: Path,
                     storage_root: str, generation_id: str, reader: ReaderServiceClient) -> None:
    """Normalize, publish, and persist all chapter text snapshots using one frozen extraction function."""
    rows: list[dict] = []
    for chapter in chapters:
        normalized = normalize_text(chapter_payload(chapter))
        rows.append(publish_chapter(storage, work_directory, storage_root, generation_id, chapter, normalized))
        if len(rows) == BATCH_SIZE:
            reader.save(generation_id, rows)
            rows = []
    if rows:
        reader.save(generation_id, rows)


def write_result(result: dict) -> None:
    """Atomically write the bounded task result document."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one audiobook text projection task."""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.environ.get("STORAGE_INTERNAL_TOKEN", ""))
    reader = ReaderServiceClient(os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                                 os.environ.get("READER_INTERNAL_TOKEN", ""))
    write_result(execute(context["parameters"], storage, reader, context_path.parent))


if __name__ == "__main__":
    main()
