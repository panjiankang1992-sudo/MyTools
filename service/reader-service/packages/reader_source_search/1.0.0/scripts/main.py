#!/usr/bin/env python3
"""Search enabled book sources through the isolated Reader Runtime."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import tempfile
import urllib.parse
import urllib.request

MAX_SOURCES = 500
MAX_RESULTS = 500


class ReaderRuntimeClient:
    """Minimal Reader Runtime HTTP client used inside one task process."""

    def __init__(self, base_url: str, secure_key: str, user_id: str):
        if not secure_key:
            raise ValueError("Reader Runtime secure key is missing")
        self._base_url = base_url.rstrip("/")
        self._headers = {"Accept": "application/json", "X-Secure-Key": secure_key, "X-User-NS": user_id}

    def replace_sources(self, sources: list[dict]) -> None:
        """Replace the task user's source snapshot in bounded batches."""
        self._request("/reader3/deleteAllBookSources", "POST", b"")
        for offset in range(0, len(sources), 100):
            body = json.dumps(sources[offset:offset + 100], ensure_ascii=False).encode("utf-8")
            self._request("/reader3/saveBookSources", "POST", body)

    def search(self, source_url: str, keyword: str, page: int) -> list[dict]:
        """Run one source search rule and return raw runtime rows."""
        query = urllib.parse.urlencode({"key": keyword, "page": page, "bookSourceUrl": source_url})
        data = self._request("/reader3/searchBook?" + query, "GET", None).get("data")
        return data if isinstance(data, list) else []

    def _request(self, path: str, method: str, body: bytes | None) -> dict:
        headers = dict(self._headers)
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self._base_url + path, data=body, headers=headers, method=method)
        with urllib.request.urlopen(request, timeout=25) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if not isinstance(payload, dict) or not payload.get("isSuccess"):
            raise RuntimeError("Reader Runtime rejected request")
        return payload


def normalized(value: object) -> str:
    """Normalize search matching and deduplication text."""
    return "".join(str(value or "").lower().split())


def normalize_results(rows: list[dict], source: dict, keyword: str, mode: str) -> list[dict]:
    """Validate, filter and normalize one source's search rows."""
    expected = normalized(keyword)
    output = []
    seen = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        name = str(row.get("name") or "").strip()
        book_url = str(row.get("bookUrl") or "").strip()
        author = str(row.get("author") or "").strip()
        if not name or not book_url:
            continue
        fields = [normalized(name), normalized(author), normalized(row.get("intro")),
                  normalized(row.get("lastChapter"))]
        matches = expected in fields[:2] if mode == "EXACT" else any(expected in field for field in fields)
        key = normalized(name)
        if not matches or key in seen:
            continue
        seen.add(key)
        output.append({
            "name": name[:300],
            "author": author[:200],
            "intro": str(row.get("intro") or "")[:4000],
            "lastChapter": str(row.get("lastChapter") or "")[:500],
            "coverUrl": str(row.get("coverUrl") or "")[:2000],
            "bookUrl": book_url[:4000],
            "sourceUrl": str(row.get("origin") or source["url"])[:2000],
            "sourceName": str(source.get("name") or source["url"])[:300],
            "sourceId": str(source["id"]),
        })
    return output


def execute(parameters: dict, client: ReaderRuntimeClient) -> dict:
    """Synchronize sources and run a bounded partial-success search."""
    keyword = str(parameters["keyword"]).strip()
    mode = str(parameters.get("mode") or "FUZZY").upper()
    page = max(1, int(parameters.get("page") or 1))
    sources = parameters.get("sources")
    if not keyword or mode not in {"FUZZY", "EXACT"}:
        raise ValueError("invalid search parameters")
    if not isinstance(sources, list) or len(sources) > MAX_SOURCES:
        raise ValueError("book source list is invalid or exceeds limit")
    snapshots = [source["snapshot"] for source in sources if isinstance(source, dict)
                 and isinstance(source.get("snapshot"), dict)]
    client.replace_sources(snapshots)
    results = []
    errors = []
    successful = 0

    def search_one(source: dict) -> list[dict]:
        return normalize_results(client.search(str(source["url"]), keyword, page), source, keyword, mode)

    concurrency = max(1, min(20, int(os.getenv("READER_SEARCH_CONCURRENCY", "20"))))
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {executor.submit(search_one, source): source for source in sources}
        for future in as_completed(futures):
            source = futures[future]
            try:
                values = future.result()
                successful += 1
                results.extend(values)
            except Exception as exception:
                errors.append({"sourceId": str(source.get("id") or ""),
                               "error": type(exception).__name__})
    deduplicated = {}
    for value in results:
        deduplicated.setdefault(normalized(value["name"]), value)
    return {
        "keyword": keyword,
        "mode": mode,
        "page": page,
        "totalSources": len(sources),
        "successfulSources": successful,
        "failedSources": len(errors),
        "errors": errors[:MAX_SOURCES],
        "results": list(deduplicated.values())[:MAX_RESULTS],
    }


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one reader source search task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = ReaderRuntimeClient(
        os.getenv("READER_RUNTIME_BASE_URL", "http://127.0.0.1:23120"),
        os.environ.get("READER_RUNTIME_SECURE_KEY", ""),
        str(parameters["userId"]),
    )
    write_result(execute(parameters, client))


if __name__ == "__main__":
    main()
