#!/usr/bin/env python3
"""Search a deterministic shard of book sources through Reader Runtime."""

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
    """Minimal isolated Reader Runtime HTTP client."""

    def __init__(self, base_url: str, secure_key: str, namespace: str):
        if not secure_key:
            raise ValueError("Reader Runtime secure key is missing")
        self._base_url = base_url.rstrip("/")
        self._headers = {"Accept": "application/json", "X-Secure-Key": secure_key, "X-User-NS": namespace}

    def replace_sources(self, sources: list[dict]) -> None:
        """Replace the execution namespace source snapshot in bounded batches."""
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


def target_assignment(parameters: dict) -> tuple[int, int]:
    """Read and validate Scheduler multi-node target metadata."""
    target = parameters.get("taskExecutionTarget")
    if not isinstance(target, dict):
        return 0, 1
    index = int(target.get("index", 0))
    count = int(target.get("count", 1))
    if count < 1 or index < 0 or index >= count:
        raise ValueError("task execution target is invalid")
    return index, count


def execute(parameters: dict, client: ReaderRuntimeClient) -> dict:
    """Synchronize and search only the source shard assigned to this node."""
    keyword = str(parameters["keyword"]).strip()
    mode = str(parameters.get("mode") or "FUZZY").upper()
    page = max(1, int(parameters.get("page") or 1))
    sources = parameters.get("sources")
    terms = parameters.get("searchTerms")
    if not keyword or mode not in {"FUZZY", "EXACT", "PROBE"}:
        raise ValueError("invalid search parameters")
    if not isinstance(terms, list) or not terms or len(terms) > 10 \
            or any(not isinstance(term, str) or not term.strip() or len(term) > 100 for term in terms):
        raise ValueError("search terms are invalid")
    if mode != "PROBE":
        terms = [keyword]
    if not isinstance(sources, list) or len(sources) > MAX_SOURCES:
        raise ValueError("book source list is invalid or exceeds limit")
    target_index, target_count = target_assignment(parameters)
    assigned = [source for source_index, source in enumerate(sources)
                if source_index % target_count == target_index]
    snapshots = [source["snapshot"] for source in assigned if isinstance(source, dict)
                 and isinstance(source.get("snapshot"), dict)]
    client.replace_sources(snapshots)
    results = []
    errors = []
    successful = 0

    def search_one(source: dict) -> list[dict]:
        values = []
        for term in terms:
            values.extend(normalize_results(client.search(str(source["url"]), term, page), source,
                                            term, "FUZZY" if mode == "PROBE" else mode))
        return values

    concurrency = max(1, min(20, int(os.getenv("READER_SEARCH_CONCURRENCY", "20"))))
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {executor.submit(search_one, source): source for source in assigned}
        for future in as_completed(futures):
            source = futures[future]
            try:
                values = future.result()
                successful += 1
                results.extend(values)
            except Exception as exception:
                errors.append({"sourceId": str(source.get("id") or ""), "error": type(exception).__name__})
    deduplicated = {}
    for value in results:
        deduplicated.setdefault(normalized(value["name"]), value)
    return {
        "keyword": keyword,
        "mode": mode,
        "page": page,
        "totalSources": len(sources),
        "assignedSources": len(assigned),
        "successfulSources": successful,
        "failedSources": len(errors),
        "target": {"index": target_index, "count": target_count},
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
    """Execute one sharded reader source search task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    namespace = f"{parameters['userId']}:task:{context['executionId']}"
    client = ReaderRuntimeClient(
        os.getenv("READER_RUNTIME_BASE_URL", "http://127.0.0.1:23120"),
        os.environ.get("READER_RUNTIME_SECURE_KEY", ""), namespace)
    write_result(execute(parameters, client))


if __name__ == "__main__":
    main()
