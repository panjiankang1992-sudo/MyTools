#!/usr/bin/env python3
"""Run one server-defined rclone copy or synchronization operation."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import time
from urllib.request import Request, urlopen


class StorageClient:
    """Call only the opaque Storage operation remote-job endpoints."""

    def __init__(self, base_url: str, token: str, opener=urlopen):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.opener = opener

    def start(self, operation_id: str) -> dict:
        """Idempotently start the server-defined transfer."""
        return self._request("POST", f"/{operation_id}/remote-job/start")

    def status(self, operation_id: str) -> dict:
        """Read the remote job and reconcile operation terminal state."""
        return self._request("GET", f"/{operation_id}/remote-job")

    def _request(self, method: str, suffix: str) -> dict:
        request = Request(
            self.base_url + "/api/internal/v1/storage/operations" + suffix,
            data=b"{}" if method == "POST" else None,
            method=method,
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"},
        )
        with self.opener(request, timeout=60) as response:
            document = json.loads(response.read().decode("utf-8"))
        if not isinstance(document, dict):
            raise RuntimeError("Storage Gateway returned a non-object response")
        return document


def execute(parameters: dict, client: StorageClient, poll_seconds: float = 5.0,
            sleeper=time.sleep) -> dict:
    """Start and poll an opaque remote transfer until its durable terminal state."""
    operation_id = str(parameters["operationId"])
    started = client.start(operation_id)
    remote_job_id = int(started.get("remoteJobId") or 0)
    if remote_job_id <= 0:
        raise RuntimeError("Storage operation did not bind a remote job")
    while True:
        status = client.status(operation_id)
        if bool(status.get("finished")):
            if not bool(status.get("success")):
                raise RuntimeError("Storage remote transfer failed")
            return {"operationId": operation_id, "remoteJobId": remote_job_id,
                    "status": "SUCCEEDED"}
        sleeper(poll_seconds)


def write_result(result: dict) -> None:
    """Atomically write the task result document."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one Storage Gateway transfer operation."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = StorageClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                           os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    write_result(execute(context["parameters"], client))


if __name__ == "__main__":
    main()
