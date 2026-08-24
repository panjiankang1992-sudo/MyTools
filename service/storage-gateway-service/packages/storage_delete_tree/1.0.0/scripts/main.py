#!/usr/bin/env python3
"""Freeze a bounded server-defined tree before starting its purge operation."""

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


class StorageClient:
    """Call only opaque operation APIs and the operation-owned Provider listing API."""

    def __init__(self, base_url: str, token: str, opener=urlopen):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.opener = opener

    def operation(self, operation_id: str) -> dict:
        """Read the server-owned delete definition."""
        return self._request("GET", f"/api/internal/v1/storage/operations/{operation_id}")

    def list(self, provider_id: str, path: str) -> list[dict]:
        """List one directory through the bounded Provider API."""
        query = urlencode({"path": path})
        return self._request("GET", f"/api/internal/v1/storage/providers/{provider_id}/objects?{query}")

    def merge(self, operation_id: str, items: list[dict]) -> dict:
        """Freeze one bounded object batch."""
        return self._request("POST", f"/api/internal/v1/storage/operations/{operation_id}/items",
                             {"items": items})

    def start(self, operation_id: str) -> dict:
        """Start the purge only after the complete preflight succeeds."""
        return self._request("POST", f"/api/internal/v1/storage/operations/{operation_id}/remote-job/start", {})

    def status(self, operation_id: str) -> dict:
        """Poll the purge and reconcile the durable operation state."""
        return self._request("GET", f"/api/internal/v1/storage/operations/{operation_id}/remote-job")

    def _request(self, method: str, path: str, payload: dict | None = None):
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(self.base_url + path, data=body, method=method,
                          headers={"Authorization": f"Bearer {self.token}",
                                   "Content-Type": "application/json"})
        with self.opener(request, timeout=120) as response:
            return json.loads(response.read().decode())


def execute(parameters: dict, client: StorageClient, poll_seconds: float = 5.0,
            sleeper=time.sleep) -> dict:
    """Freeze the full bounded tree, then start and poll the opaque purge."""
    operation_id = str(parameters["operationId"])
    operation = client.operation(operation_id)
    if operation.get("operationType") != "DELETE_TREE" or operation.get("status") != "RUNNING":
        raise ValueError("storage delete operation is invalid")
    provider_id = str(operation["providerId"])
    root_path = str(operation.get("sourcePath") or "")
    maximum = int(operation["maximumObjects"])
    if not root_path or maximum <= 0 or maximum > 1_000_000:
        raise ValueError("storage delete boundary is invalid")
    queue = deque([root_path])
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
            raise ValueError("storage delete exceeded maximumObjects")
        for offset in range(0, len(items), BATCH_SIZE):
            client.merge(operation_id, items[offset:offset + BATCH_SIZE])
        for item in items:
            if item.get("directory"):
                queue.append(str(item["path"]))
    started = client.start(operation_id)
    remote_job_id = int(started.get("remoteJobId") or 0)
    if remote_job_id <= 0:
        raise RuntimeError("storage delete did not bind a remote job")
    while True:
        status = client.status(operation_id)
        if bool(status.get("finished")):
            if not bool(status.get("success")):
                raise RuntimeError("storage delete failed")
            return {"operationId": operation_id, "remoteJobId": remote_job_id,
                    "status": "SUCCEEDED", "itemCount": item_count}
        sleeper(poll_seconds)


def main() -> None:
    """Run one bounded deletion task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = StorageClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                           os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    result = execute(context["parameters"], client)
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


if __name__ == "__main__":
    main()
