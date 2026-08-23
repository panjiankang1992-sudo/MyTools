#!/usr/bin/env python3
"""Build a bounded ebook catalog and persist it through Reader Service."""

from __future__ import annotations

import json
import os
from pathlib import Path, PurePosixPath
import re
import tempfile
import urllib.request
import xml.etree.ElementTree as element_tree
import zipfile

from mytools_task_sdk.ebook import (
    decode_text, first_local_text, local_name, read_zip_entry, safe_zip_name, validate_archive,
)
from mytools_task_sdk.storage import StorageGatewayClient, parse_storage_uri

MAX_INPUT_BYTES = 512 * 1024 * 1024
MAX_ENTRIES = 20_000
MAX_EPUB_ENTRIES = 10_000
MAX_EPUB_EXPANDED_BYTES = 512 * 1024 * 1024
MAX_XHTML_BYTES = 4 * 1024 * 1024
MAX_PDF_SCAN_BYTES = 8 * 1024 * 1024
CHAPTER_PATTERN = re.compile(
    r"(?i)^\s*(?:\u7b2c[0-9\u96f6\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d"
    r"\u5341\u767e\u5343\u4e07\u4e24]+[\u7ae0\u8282\u5377\u56de]|chapter\s+\d+).*$")
PDF_PAGE_PATTERN = re.compile(rb"/Type\s*/Page\b")


class CatalogWriter:
    """Reader Service internal catalog batch client."""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Reader Service internal token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def save(self, request_id: str, entries: list[dict], replace: bool) -> None:
        """Persist one bounded catalog batch."""
        body = json.dumps({"replace": replace, "entries": entries}, ensure_ascii=False,
                          separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/ebook-imports/{request_id}/catalog",
            data=body, method="POST", headers={"Authorization": f"Bearer {self._token}",
                                                "Content-Type": "application/json",
                                                "Accept": "application/json"})
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()


def text_catalog(path: Path, fallback_title: str) -> list[dict]:
    """Build byte-offset catalog entries from text chapter headings."""
    headings = []
    offset = 0
    with path.open("rb") as source:
        for line in source:
            title = decode_text(line).strip()
            if CHAPTER_PATTERN.match(title):
                headings.append((offset, title[:500]))
                if len(headings) > MAX_ENTRIES:
                    raise ValueError("Text catalog exceeds limit")
            offset += len(line)
    if not headings:
        return [{"index": 0, "title": fallback_title[:500] or "Content",
                 "resourceRef": "text:0", "startOffset": 0, "endOffset": offset}]
    entries = []
    for index, (start, title) in enumerate(headings):
        end = headings[index + 1][0] if index + 1 < len(headings) else offset
        entries.append({"index": index, "title": title, "resourceRef": f"text:{start}",
                        "startOffset": start, "endOffset": end})
    return entries


def epub_catalog(path: Path) -> list[dict]:
    """Build EPUB spine entries with safe archive resource references."""
    with zipfile.ZipFile(path) as archive:
        validate_archive(archive, MAX_EPUB_ENTRIES, MAX_EPUB_EXPANDED_BYTES)
        container = element_tree.fromstring(read_zip_entry(archive, "META-INF/container.xml", 1024 * 1024))
        package_path = next((safe_zip_name("", item.attrib.get("full-path", ""))
                             for item in container.iter() if local_name(item) == "rootfile"), "")
        if not package_path or package_path not in archive.namelist():
            raise ValueError("EPUB package document is missing")
        package = element_tree.fromstring(read_zip_entry(archive, package_path, 4 * 1024 * 1024))
        manifest = {}
        for item in package.iter():
            if local_name(item) == "item" and item.attrib.get("id"):
                manifest[item.attrib["id"]] = safe_zip_name(package_path, item.attrib.get("href", ""))
        resources = [manifest.get(item.attrib.get("idref", ""), "") for item in package.iter()
                     if local_name(item) == "itemref"]
        resources = [resource for resource in resources if resource]
        if not resources or len(resources) > MAX_ENTRIES:
            raise ValueError("EPUB spine is empty or exceeds limit")
        entries = []
        for index, resource in enumerate(resources):
            title = PurePosixPath(resource).stem
            try:
                document = element_tree.fromstring(read_zip_entry(archive, resource, MAX_XHTML_BYTES))
                title = first_local_text(document, "title") or first_local_text(document, "h1") or title
            except (KeyError, ValueError, element_tree.ParseError):
                pass
            entries.append({"index": index, "title": title[:500] or f"Chapter {index + 1}",
                            "resourceRef": "epub:" + resource,
                            "startOffset": None, "endOffset": None})
        return entries


def pdf_catalog(path: Path) -> list[dict]:
    """Build bounded page references from the same partial PDF scan as metadata extraction."""
    with path.open("rb") as source:
        pages = max(1, len(PDF_PAGE_PATTERN.findall(source.read(MAX_PDF_SCAN_BYTES))))
    if pages > MAX_ENTRIES:
        raise ValueError("PDF page catalog exceeds limit")
    return [{"index": index, "title": f"Page {index + 1}", "resourceRef": f"pdf:{index + 1}",
             "startOffset": None, "endOffset": None} for index in range(pages)]


def generic_catalog(file_name: str) -> list[dict]:
    """Return one safe fallback entry for formats without deterministic section offsets."""
    return [{"index": 0, "title": Path(file_name).stem[:500] or "Content",
             "resourceRef": "binary:0", "startOffset": None, "endOffset": None}]


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
        raise ValueError("Catalog input storage URI is missing")
    if not file_name:
        _, relative_path = parse_storage_uri(storage_uri)
        file_name = PurePosixPath(relative_path).name
    return storage_uri, file_name


def execute(context: dict, storage: StorageGatewayClient, writer: CatalogWriter,
            work_directory: Path) -> dict:
    """Download, build, and batch-persist one ebook catalog."""
    parameters = context["parameters"]
    request_id = str(parameters["requestId"])
    storage_uri, file_name = input_from_context(context)
    extension = Path(file_name).suffix.lower().lstrip(".")
    input_path = work_directory / ("input." + (extension or "bin"))
    storage.download(storage_uri, input_path, MAX_INPUT_BYTES)
    if extension in {"txt", "md"}:
        entries = text_catalog(input_path, Path(file_name).stem)
    elif extension == "epub":
        entries = epub_catalog(input_path)
    elif extension == "pdf":
        entries = pdf_catalog(input_path)
    else:
        entries = generic_catalog(file_name)
    for offset in range(0, len(entries), 200):
        writer.save(request_id, entries[offset:offset + 200], replace=offset == 0)
    return {"requestId": request_id, "format": extension.upper(), "entryCount": len(entries)}


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one catalog build step."""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.environ.get("STORAGE_INTERNAL_TOKEN", ""))
    writer = CatalogWriter(os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                           os.environ.get("READER_INTERNAL_TOKEN", ""))
    write_result(execute(context, storage, writer, context_path.parent))


if __name__ == "__main__":
    main()
