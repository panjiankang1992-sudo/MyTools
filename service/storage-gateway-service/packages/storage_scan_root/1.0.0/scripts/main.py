#!/usr/bin/env python3
"""Scan one remote Provider tree and incrementally merge its object index."""

from __future__ import annotations

import json
import os
from collections import deque
from pathlib import Path
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BATCH_SIZE = 500


class StorageClient:
    """Call only the Storage Gateway Provider and operation APIs."""

    def __init__(self, base_url: str, token: str, opener=urlopen):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.opener = opener

    def list(self, provider_id: str, path: str) -> list[dict]:
        """List one remote directory."""
        query = urlencode({"path": path})
        return self._request("GET", f"/api/internal/v1/storage/providers/{provider_id}/objects?{query}")

    def merge(self, operation_id: str, items: list[dict]) -> dict:
        """Idempotently merge one bounded item batch."""
        return self._request("POST", f"/api/internal/v1/storage/operations/{operation_id}/items",
                             {"items": items})

    def finish(self, operation_id: str, status: str, error_code: str | None = None) -> dict:
        """Set an operation terminal state."""
        return self._request("POST", f"/api/internal/v1/storage/operations/{operation_id}/finish",
                             {"status": status, "errorCode": error_code})

    def _request(self, method: str, path: str, payload: dict | None = None):
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(self.base_url + path, data=body, method=method,
                          headers={"Authorization": f"Bearer {self.token}",
                                   "Content-Type": "application/json"})
        with self.opener(request, timeout=120) as response:
            return json.loads(response.read().decode())


def execute(parameters: dict, client: StorageClient) -> dict:
    """Breadth-first scan a bounded tree and persist batches as progress."""
    operation_id = str(parameters["operationId"])
    provider_id = str(parameters["providerId"])
    maximum = int(parameters["maximumObjects"])
    if maximum <= 0 or maximum > 1_000_000:
        raise ValueError("maximumObjects is invalid")
    queue = deque([str(parameters.get("rootPath") or "")])
    visited = set()
    item_count = 0
    while queue:
        current = queue.popleft()
        if current in visited:
            continue
        visited.add(current)
        items = client.list(provider_id, current)
        item_count += len(items)
        if item_count > maximum:
            raise ValueError("storage scan exceeded maximumObjects")
        for offset in range(0, len(items), BATCH_SIZE):
            client.merge(operation_id, items[offset:offset + BATCH_SIZE])
        for item in items:
            if item.get("directory"):
                queue.append(str(item["path"]))
    completed = client.finish(operation_id, "SUCCEEDED")
    return {"operationId": operation_id, "status": "SUCCEEDED",
            "itemCount": int(completed.get("itemCount", item_count))}


def write_result(result: dict) -> None:
    """Atomically write the task result document."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one storage root scan."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = StorageClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                           os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    write_result(execute(context["parameters"], client))


if __name__ == "__main__":
    main()
