#!/usr/bin/env python3
"""Fetch selected source chapters and persist them through Reader Service."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request

from mytools_task_sdk.reader_runtime import ReaderRuntimeClient

MAX_REQUESTED = 100
BATCH_SIZE = 20
TTL_SECONDS = 86_400


class ReaderServiceClient:
    """Authenticated client for bounded internal chapter writes."""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Reader Service internal token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def save(self, request_id: str, chapters: list[dict]) -> None:
        """Persist one bounded chapter batch."""
        body = json.dumps({"chapters": chapters}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/chapter-prefetches/{request_id}/chapters",
            data=body,
            headers={"Authorization": f"Bearer {self._token}", "Content-Type": "application/json",
                     "Accept": "application/json"},
            method="POST")
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status < 200 or response.status >= 300:
                raise RuntimeError("Reader Service rejected chapter batch")


def execute(parameters: dict, runtime: ReaderRuntimeClient, reader: ReaderServiceClient) -> dict:
    """Fetch requested chapter indexes and persist bounded batches."""
    indexes = sorted(set(int(value) for value in parameters["chapterIndexes"]))
    if not indexes or len(indexes) > MAX_REQUESTED or indexes[0] < 0:
        raise ValueError("chapter index selection is invalid")
    runtime.install_source(parameters["sourceSnapshot"])
    _, catalog = runtime.catalog(str(parameters["sourceUrl"]), str(parameters["bookUrl"]))
    if indexes[-1] >= len(catalog):
        raise ValueError("chapter index exceeds catalog")
    request_id = str(parameters["requestId"])
    rows: list[dict] = []
    cached = 0
    for index in indexes:
        chapter = catalog[index]
        chapter_url = str(chapter.get("url") or "").strip()
        if not chapter_url:
            raise ValueError("chapter URL is missing")
        content = runtime.content(str(parameters["sourceUrl"]), chapter_url)
        payload = content.encode("utf-8")
        rows.append({"index": index, "title": str(chapter.get("title") or "Chapter")[:500],
                     "chapterUrl": chapter_url, "content": content,
                     "sha256": hashlib.sha256(payload).hexdigest(), "sizeBytes": len(payload),
                     "ttlSeconds": TTL_SECONDS})
        if len(rows) == BATCH_SIZE:
            reader.save(request_id, rows)
            cached += len(rows)
            rows = []
    if rows:
        reader.save(request_id, rows)
        cached += len(rows)
    return {"requestId": request_id, "requestedCount": len(indexes), "cachedCount": cached}


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one chapter prefetch task."""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    parameters = context["parameters"]
    namespace = f"{parameters['ownerId']}:prefetch:{context['executionId']}"
    runtime = ReaderRuntimeClient(os.getenv("READER_RUNTIME_BASE_URL", "http://127.0.0.1:23120"),
                                  os.environ.get("READER_RUNTIME_SECURE_KEY", ""), namespace)
    reader = ReaderServiceClient(os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                                 os.environ.get("READER_INTERNAL_TOKEN", ""))
    write_result(execute(parameters, runtime, reader))


if __name__ == "__main__":
    main()
