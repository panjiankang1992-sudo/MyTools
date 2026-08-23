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
