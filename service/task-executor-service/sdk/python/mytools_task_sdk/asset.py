"""Authenticated client for the Asset Registry atomic API."""

from __future__ import annotations

import json
import urllib.request


class AssetRegistryClient:
    """Register verified content without exposing service credentials in task parameters."""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Asset Registry internal token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def register(self, payload: dict) -> dict:
        """Idempotently register content, source, and an optional initial location."""
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            self._base_url + "/internal/v1/assets", data=body,
            headers={"Authorization": f"Bearer {self._token}", "Content-Type": "application/json",
                     "Accept": "application/json"}, method="POST")
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.loads(response.read().decode("utf-8"))
        if not isinstance(result, dict) or not result.get("id") or not result.get("version"):
            raise RuntimeError("Asset Registry returned an invalid response")
        return result

    def register_artifact(self, asset_id: str, payload: dict, task_context: dict) -> dict:
        """Idempotently link a derived asset to its parent content asset."""
        required = ("taskInstanceId", "stepName", "fencingToken")
        if any(task_context.get(key) in (None, "") for key in required):
            raise ValueError("Task execution fence context is missing")
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            self._base_url + f"/internal/v1/assets/{asset_id}/artifacts", data=body,
            headers={"Authorization": f"Bearer {self._token}", "Content-Type": "application/json",
                     "Accept": "application/json",
                     "X-Task-Instance-Id": str(task_context["taskInstanceId"]),
                     "X-Task-Step-Name": str(task_context["stepName"]),
                     "X-Task-Business-Key": str(payload["idempotencyKey"]),
                     "X-Task-Fencing-Token": str(task_context["fencingToken"])}, method="POST")
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.loads(response.read().decode("utf-8"))
        if not isinstance(result, dict) or not result.get("id") or not result.get("version"):
            raise RuntimeError("Asset Registry returned an invalid artifact response")
        return result
