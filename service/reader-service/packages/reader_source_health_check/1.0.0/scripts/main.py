#!/usr/bin/env python3
"""Check a deterministic shard of book sources through Reader Runtime."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import tempfile
import time
import urllib.parse
import urllib.request

MAX_SOURCES = 500


class ReaderRuntimeClient:
    """Minimal isolated Reader Runtime client for source checks."""

    def __init__(self, base_url: str, secure_key: str, namespace: str):
        if not secure_key:
            raise ValueError("Reader Runtime secure key is missing")
        self._base_url = base_url.rstrip("/")
        self._headers = {"Accept": "application/json", "X-Secure-Key": secure_key, "X-User-NS": namespace}

    def replace_sources(self, sources: list[dict]) -> None:
        """Replace sources in the execution-specific namespace."""
        self._request("/reader3/deleteAllBookSources", "POST", b"")
        for offset in range(0, len(sources), 100):
            body = json.dumps(sources[offset:offset + 100], ensure_ascii=False).encode("utf-8")
            self._request("/reader3/saveBookSources", "POST", body)

    def check(self, source_url: str, keyword: str) -> None:
        """Execute one source search rule; an empty successful response is healthy."""
        query = urllib.parse.urlencode({"key": keyword, "page": 1, "bookSourceUrl": source_url})
        self._request("/reader3/searchBook?" + query, "GET", None)

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


def target_assignment(parameters: dict) -> tuple[int, int]:
    """Read Scheduler target metadata with a single-node fallback."""
    target = parameters.get("taskExecutionTarget")
    if not isinstance(target, dict):
        return 0, 1
    index = int(target.get("index", 0))
    count = int(target.get("count", 1))
    if count < 1 or index < 0 or index >= count:
        raise ValueError("task execution target is invalid")
    return index, count


def execute(parameters: dict, client: ReaderRuntimeClient) -> dict:
    """Check only sources assigned to the current immutable execution target."""
    sources = parameters.get("sources")
    keyword = str(parameters.get("keyword") or "test").strip()
    if not isinstance(sources, list) or len(sources) > MAX_SOURCES or not keyword:
        raise ValueError("source health check parameters are invalid")
    target_index, target_count = target_assignment(parameters)
    assigned = [source for index, source in enumerate(sources) if index % target_count == target_index]
    snapshots = [source["snapshot"] for source in assigned if isinstance(source, dict)
                 and isinstance(source.get("snapshot"), dict)]
    client.replace_sources(snapshots)

    def check_one(source: dict) -> dict:
        started = time.monotonic()
        try:
            client.check(str(source["url"]), keyword)
            return {"sourceId": str(source["id"]), "status": "HEALTHY",
                    "latencyMillis": max(0, round((time.monotonic() - started) * 1000))}
        except Exception as exception:
            return {"sourceId": str(source.get("id") or ""), "status": "UNHEALTHY",
                    "latencyMillis": max(0, round((time.monotonic() - started) * 1000)),
                    "errorCode": type(exception).__name__}

    concurrency = max(1, min(20, int(os.getenv("READER_HEALTH_CONCURRENCY", "10"))))
    results = []
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(check_one, source) for source in assigned]
        for future in as_completed(futures):
            results.append(future.result())
    healthy = sum(1 for result in results if result["status"] == "HEALTHY")
    return {"requestId": str(parameters["requestId"]),
            "target": {"index": target_index, "count": target_count},
            "checked": len(results), "healthy": healthy, "unhealthy": len(results) - healthy,
            "results": results}


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one source health-check shard."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    namespace = f"{parameters['ownerId']}:health:{context['executionId']}"
    client = ReaderRuntimeClient(os.getenv("READER_RUNTIME_BASE_URL", "http://127.0.0.1:23120"),
                                 os.environ.get("READER_RUNTIME_SECURE_KEY", ""), namespace)
    write_result(execute(parameters, client))


if __name__ == "__main__":
    main()
