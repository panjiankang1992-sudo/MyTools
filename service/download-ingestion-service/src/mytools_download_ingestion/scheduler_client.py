"""HTTP adapter for the Task Scheduler public API."""

from __future__ import annotations

import json
from urllib.request import Request, urlopen
from uuid import UUID


class TaskSchedulerHttpClient:
    """Create, query, and cancel scheduler task instances over HTTP."""

    def __init__(self, base_url: str, timeout_seconds: float = 10):
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds

    def create_task(self, *, task_name: str, idempotency_key: str,
                    business_id: str, parameters: dict) -> UUID:
        """Idempotently create one scheduler task instance."""
        payload = {
            "taskName": task_name,
            "idempotencyKey": idempotency_key,
            "businessType": "DOWNLOAD_REQUEST",
            "businessId": business_id,
            "parentTaskInstanceId": None,
            "priority": 50,
            "parameters": parameters,
        }
        result = self._request("POST", "/api/v1/task-instances", payload)
        return UUID(result["id"])

    def get_task(self, task_id: UUID) -> dict:
        """Return the current scheduler task representation."""
        return self._request("GET", f"/api/v1/task-instances/{task_id}")

    def cancel_task(self, task_id: UUID) -> dict:
        """Request cancellation of one scheduler task."""
        return self._request("POST", f"/api/v1/task-instances/{task_id}/cancel", {})

    def _request(self, method: str, path: str, payload: dict | None = None) -> dict:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = Request(
            f"{self._base_url}{path}", data=body, method=method,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        with urlopen(request, timeout=self._timeout_seconds) as response:
            return json.loads(response.read().decode("utf-8"))
