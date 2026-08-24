#!/usr/bin/env python3
"""Freeze one Provider tree and orchestrate native object-copy child tasks."""

from __future__ import annotations

from collections import deque
import json
import os
from pathlib import Path
import tempfile
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BATCH_SIZE = 500
TERMINAL = {"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"}


class StorageClient:
    """Call the bounded Storage Gateway APIs used by the parent task."""

    def __init__(self, base_url: str, token: str, opener=urlopen):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.opener = opener

    def operation(self, operation_id: str) -> dict:
        """Read one operation."""
        return self._request("GET", f"/api/internal/v1/storage/operations/{operation_id}")

    def list(self, provider_id: str, path: str) -> list[dict]:
        """List one Provider directory."""
        return self._request("GET", f"/api/internal/v1/storage/providers/{provider_id}/objects?"
                             + urlencode({"path": path}))

    def merge(self, operation_id: str, items: list[dict]) -> dict:
        """Freeze one item batch."""
        return self._request("POST", f"/api/internal/v1/storage/operations/{operation_id}/items",
                             {"items": items})

    def create_child(self, operation_id: str, source_path: str) -> dict:
        """Create an idempotent native object-copy child."""
        return self._request("POST",
                             f"/api/internal/v1/storage/operations/{operation_id}/native-tree/children",
                             {"sourceObjectPath": source_path})

    def finish(self, operation_id: str) -> dict:
        """Mark the parent successful after every child succeeds."""
        return self._request("POST", f"/api/internal/v1/storage/operations/{operation_id}/finish",
                             {"status": "SUCCEEDED", "errorCode": None})

    def _request(self, method: str, path: str, payload: dict | None = None):
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(self.base_url + path, data=body, method=method,
                          headers={"Authorization": f"Bearer {self.token}",
                                   "Content-Type": "application/json"})
        with self.opener(request, timeout=120) as response:
            return json.loads(response.read().decode())


def execute(parameters: dict, client: StorageClient, pause=time.sleep) -> dict:
    """Freeze the full tree before creating and polling child operations."""
    operation_id = str(parameters["operationId"])
    parent = client.operation(operation_id)
    if parent.get("operationType") != "COPY_TREE_NATIVE" or parent.get("status") != "RUNNING":
        raise ValueError("native tree operation is invalid")
    maximum = int(parent["maximumObjects"])
    queue = deque([str(parent.get("sourcePath") or "")])
    visited: set[str] = set()
    files: list[str] = []
    item_count = 0
    while queue:
        current = queue.popleft()
        if current in visited:
            continue
        visited.add(current)
        items = client.list(str(parent["providerId"]), current)
        item_count += len(items)
        if item_count > maximum:
            raise ValueError("native tree copy exceeded maximumObjects")
        for offset in range(0, len(items), BATCH_SIZE):
            client.merge(operation_id, items[offset:offset + BATCH_SIZE])
        for item in items:
            path = str(item["path"])
            if item.get("directory"):
                queue.append(path)
            else:
                files.append(path)

    children = [client.create_child(operation_id, source_path) for source_path in files]
    pending = {str(child["id"]) for child in children}
    while pending:
        for child_id in tuple(pending):
            child = client.operation(child_id)
            status = str(child["status"])
            if status == "SUCCEEDED":
                pending.remove(child_id)
            elif status in TERMINAL:
                raise RuntimeError(f"native tree child failed: {child_id}:{status}")
        if pending:
            pause(2)
    client.finish(operation_id)
    return {"operationId": operation_id, "status": "SUCCEEDED",
            "itemCount": item_count, "childCount": len(children)}


def write_result(result: dict) -> None:
    """Atomically write the task result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run one native tree copy parent task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = StorageClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                           os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    write_result(execute(context["parameters"], client))


if __name__ == "__main__":
    main()
