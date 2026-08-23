#!/usr/bin/env python3
"""Import one source-backed book and atomically publish it through Storage Gateway."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile

from mytools_task_sdk.reader_runtime import ReaderRuntimeClient
from mytools_task_sdk.storage import StorageGatewayClient

MAX_CHAPTERS = 20_000
MAX_CHAPTER_BYTES = 10 * 1024 * 1024
MAX_OUTPUT_BYTES = 512 * 1024 * 1024
INVALID_FILENAME = re.compile(r"[\x00-\x1f\x7f/\\:*?\"<>|]")
def safe_title(value: object) -> str:
    """Create a bounded display and file title."""
    normalized = INVALID_FILENAME.sub("_", str(value or "").strip())
    normalized = " ".join(normalized.split()) or "Imported book"
    return normalized[:180]


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
