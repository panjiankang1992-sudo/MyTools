#!/usr/bin/env python3
"""Extract deterministic ebook metadata from a managed storage artifact."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import tempfile
import urllib.parse
import xml.etree.ElementTree as element_tree
import zipfile

from mytools_task_sdk.storage import StorageGatewayClient, parse_storage_uri

MAX_INPUT_BYTES = 512 * 1024 * 1024
MAX_TEXT_BYTES = 32 * 1024 * 1024
MAX_EPUB_ENTRY_BYTES = 10 * 1024 * 1024
MAX_EPUB_ENTRIES = 10_000
MAX_EPUB_EXPANDED_BYTES = 512 * 1024 * 1024
MAX_PDF_SCAN_BYTES = 8 * 1024 * 1024
MAX_MOBI_SCAN_BYTES = 4 * 1024 * 1024
CHAPTER_PATTERN = re.compile(
    r"(?im)^\s*(?:\u7b2c[0-9\u96f6\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d"
    r"\u5341\u767e\u5343\u4e07\u4e24]+[\u7ae0\u8282\u5377\u56de]|chapter\s+\d+).*$")
PDF_PAGE_PATTERN = re.compile(rb"/Type\s*/Page\b")
PDF_TITLE_PATTERN = re.compile(rb"/Title\s*\(([^)]{1,500})\)")
PDF_AUTHOR_PATTERN = re.compile(rb"/Author\s*\(([^)]{1,300})\)")


def base_metadata(file_name: str) -> dict:
    """Build the common bounded metadata shape."""
    suffix = Path(file_name).suffix.lower().lstrip(".")
    title = Path(file_name).stem.replace("_", " ").strip() or "Untitled"
    return {"status": "PARTIAL", "format": suffix.upper(), "title": title[:300], "author": "",
            "description": "", "language": "", "chapterCount": 1, "wordCount": 0,
            "parserName": "filename-v1", "coverStorageUri": None, "errorCode": None}


def decode_text(data: bytes) -> str:
    """Decode strict UTF-8 first and fall back to common legacy Chinese encoding."""
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return data.decode("gb18030", errors="replace")


def extract_text(path: Path, metadata: dict) -> tuple[dict, bytes | None, str | None]:
    """Extract bounded TXT or Markdown metadata."""
    metadata["parserName"] = "txt-utf8-v1"
    if path.stat().st_size > MAX_TEXT_BYTES:
        metadata["parserName"] = "txt-size-limited-v1"
        return metadata, None, None
    content = decode_text(path.read_bytes())
    marker = "[Original filename] "
    first_line, _, remaining = content.partition("\n")
    if first_line.rstrip().startswith(marker) and len(first_line) <= 2048:
        original = first_line.rstrip()[len(marker):].strip()
        if original:
            metadata["title"] = (Path(original).stem.replace("_", " ").strip() or metadata["title"])[:300]
        content = remaining
    metadata["wordCount"] = sum(1 for value in content if not value.isspace())
    metadata["chapterCount"] = max(1, len(CHAPTER_PATTERN.findall(content)))
    metadata["language"] = "zh" if any("\u4e00" <= value <= "\u9fff" for value in content[:20_000]) else ""
    metadata["status"] = "READY"
    return metadata, None, None


def safe_zip_name(parent: str, href: str) -> str:
    """Resolve an EPUB href without allowing archive traversal."""
    if not href or "\\" in href:
        return ""
    decoded = urllib.parse.unquote(href)
    path = PurePosixPath(parent).parent.joinpath(decoded)
    parts = []
    for part in path.parts:
        if part in {"", "."}:
            continue
        if part == "..":
            if not parts:
                return ""
            parts.pop()
        else:
            parts.append(part)
    return "/".join(parts)


def read_entry(archive: zipfile.ZipFile, name: str, limit: int) -> bytes:
    """Read one regular bounded EPUB entry."""
    info = archive.getinfo(name)
    if info.is_dir() or info.file_size > limit or info.compress_size > 0 and info.file_size > info.compress_size * 1000:
        raise ValueError("EPUB entry is invalid or too large")
    with archive.open(info) as source:
        content = source.read(limit + 1)
    if len(content) > limit:
        raise ValueError("EPUB entry exceeds limit")
    return content


def local_text(root: element_tree.Element, name: str) -> str:
    """Return the first nonblank element by namespace-independent local name."""
    for item in root.iter():
        if item.tag.rsplit("}", 1)[-1] == name and item.text and item.text.strip():
            return item.text.strip()
    return ""


def extract_epub(path: Path, metadata: dict) -> tuple[dict, bytes | None, str | None]:
    """Extract OPF metadata and a bounded cover from a safe EPUB archive."""
    metadata["parserName"] = "epub-opf-v1"
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_EPUB_ENTRIES or sum(max(0, info.file_size) for info in infos) > MAX_EPUB_EXPANDED_BYTES:
            raise ValueError("EPUB archive exceeds limits")
        container = element_tree.fromstring(read_entry(archive, "META-INF/container.xml", 1024 * 1024))
        package_path = ""
        for item in container.iter():
            if item.tag.rsplit("}", 1)[-1] == "rootfile":
                candidate = safe_zip_name("", item.attrib.get("full-path", ""))
                if candidate in archive.namelist():
                    package_path = candidate
                    break
        if not package_path:
            raise ValueError("EPUB package document is missing")
        package = element_tree.fromstring(read_entry(archive, package_path, 4 * 1024 * 1024))
        metadata["title"] = (local_text(package, "title") or metadata["title"])[:300]
        metadata["author"] = local_text(package, "creator")[:200]
        metadata["description"] = local_text(package, "description")[:4000]
        metadata["language"] = local_text(package, "language")[:32]
        metadata["chapterCount"] = max(1, sum(
            1 for item in package.iter() if item.tag.rsplit("}", 1)[-1] == "itemref"))
        cover_id = ""
        for item in package.iter():
            if item.tag.rsplit("}", 1)[-1] == "meta" and item.attrib.get("name", "").lower() == "cover":
                cover_id = item.attrib.get("content", "")
        cover = None
        extension = None
        for item in package.iter():
            if item.tag.rsplit("}", 1)[-1] != "item":
                continue
            explicit = "cover-image" in item.attrib.get("properties", "")
            legacy = bool(cover_id) and cover_id == item.attrib.get("id")
            if not (explicit or legacy) or not item.attrib.get("media-type", "").startswith("image/"):
                continue
            entry = safe_zip_name(package_path, item.attrib.get("href", ""))
            cover = read_entry(archive, entry, MAX_EPUB_ENTRY_BYTES)
            extension = image_extension(cover)
            if not extension:
                raise ValueError("EPUB cover format is unsupported")
            break
        metadata["status"] = "READY"
        return metadata, cover, extension


def pdf_value(pattern: re.Pattern[bytes], data: bytes) -> str:
    """Decode one basic uncompressed PDF info value."""
    match = pattern.search(data)
    if not match:
        return ""
    return match.group(1).decode("latin-1", errors="replace").replace(r"\(", "(").replace(r"\)", ")") \
        .replace(r"\n", " ").replace(r"\r", " ").strip()


def extract_pdf(path: Path, metadata: dict) -> tuple[dict, bytes | None, str | None]:
    """Perform the same bounded partial PDF scan as the legacy service."""
    metadata["parserName"] = "pdf-basic-v1"
    with path.open("rb") as source:
        data = source.read(MAX_PDF_SCAN_BYTES)
    metadata["title"] = (pdf_value(PDF_TITLE_PATTERN, data) or metadata["title"])[:300]
    metadata["author"] = pdf_value(PDF_AUTHOR_PATTERN, data)[:200]
    metadata["chapterCount"] = max(1, len(PDF_PAGE_PATTERN.findall(data)))
    return metadata, None, None


def decode_book_text(value: bytes) -> str:
    """Decode MOBI text fields with deterministic fallbacks."""
    return decode_text(value).replace("\x00", "").strip()


def extract_mobi(path: Path, metadata: dict) -> tuple[dict, bytes | None, str | None]:
    """Extract MOBI header and EXTH title and author fields."""
    metadata["parserName"] = "mobi-header-v1"
    with path.open("rb") as source:
        data = source.read(MAX_MOBI_SCAN_BYTES)
    if len(data) < 100:
        raise ValueError("MOBI header is incomplete")
    record_offset = struct.unpack_from(">I", data, 78)[0]
    mobi_offset = record_offset + 16
    if mobi_offset + 92 > len(data) or data[mobi_offset:mobi_offset + 4] != b"MOBI":
        raise ValueError("MOBI signature is missing")
    full_name_offset, full_name_length = struct.unpack_from(">II", data, mobi_offset + 84)
    start = record_offset + full_name_offset
    if start >= 0 and full_name_length >= 0 and start + full_name_length <= len(data):
        metadata["title"] = (decode_book_text(data[start:start + full_name_length]) or metadata["title"])[:300]
    header_length = struct.unpack_from(">I", data, mobi_offset + 4)[0]
    cursor = mobi_offset + header_length
    if cursor + 12 <= len(data) and data[cursor:cursor + 4] == b"EXTH":
        record_count = min(1000, struct.unpack_from(">I", data, cursor + 8)[0])
        cursor += 12
        for _ in range(record_count):
            if cursor + 8 > len(data):
                break
            record_type, size = struct.unpack_from(">II", data, cursor)
            if size < 8 or cursor + size > len(data):
                break
            value = decode_book_text(data[cursor + 8:cursor + size])
            if record_type == 100 and value:
                metadata["author"] = value[:200]
            if record_type == 503 and value:
                metadata["title"] = value[:300]
            cursor += size
    return metadata, None, None


def image_extension(data: bytes) -> str | None:
    """Recognize supported cover formats by signature."""
    if data.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if data.startswith(b"\x89PNG"):
        return "png"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    return None


def input_from_context(context: dict) -> tuple[str, str]:
    """Resolve standalone parameters or the preceding import step output."""
    parameters = context["parameters"]
    storage_uri = str(parameters.get("storageUri") or "")
    file_name = str(parameters.get("fileName") or "")
    if not storage_uri:
        previous = context.get("stepOutputs", {}).get("import_ebook", {})
        storage_uri = str(previous.get("storageUri") or "")
        file_name = file_name or str(previous.get("title") or "Imported book") + ".txt"
    if not storage_uri:
        raise ValueError("Metadata input storage URI is missing")
    if not file_name:
        _, relative_path = parse_storage_uri(storage_uri)
        file_name = PurePosixPath(relative_path).name
    return storage_uri, file_name


def execute(context: dict, storage: StorageGatewayClient, work_directory: Path) -> dict:
    """Download, extract, and optionally publish a cover artifact."""
    parameters = context["parameters"]
    request_id = str(parameters["requestId"])
    storage_uri, file_name = input_from_context(context)
    input_path = work_directory / ("input" + Path(file_name).suffix.lower())
    storage.download(storage_uri, input_path, MAX_INPUT_BYTES)
    metadata = base_metadata(file_name)
    extractor = {"txt": extract_text, "md": extract_text, "epub": extract_epub,
                 "pdf": extract_pdf, "mobi": extract_mobi, "azw3": extract_mobi}.get(
        input_path.suffix.lower().lstrip("."))
    try:
        cover = None
        extension = None
        if extractor:
            metadata, cover, extension = extractor(input_path, metadata)
        if cover and extension:
            cover_path = work_directory / f"cover.{extension}"
            cover_path.write_bytes(cover)
            digest = hashlib.sha256(cover).hexdigest()
            metadata["coverStorageUri"] = storage.publish(
                cover_path, str(parameters["storageRoot"]),
                f"ebooks/covers/{request_id}/{digest[:16]}.{extension}",
                f"reader-cover:{request_id}:v1", len(cover), digest)
    except Exception as exception:
        metadata["status"] = "FAILED"
        metadata["errorCode"] = type(exception).__name__
    return {"requestId": request_id, **metadata}


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one metadata extraction step."""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.environ.get("STORAGE_INTERNAL_TOKEN", ""))
    write_result(execute(context, storage, context_path.parent))


if __name__ == "__main__":
    main()
