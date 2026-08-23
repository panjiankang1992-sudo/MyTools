"""下载接入服务 HTTP 客户端。"""

from __future__ import annotations

import json
from urllib.request import Request, urlopen
from uuid import UUID

from .models import AcceptLegacyEvent


class DownloadIngestionHttpClient:
    """通过内部令牌调用 Download Ingestion。"""

    def __init__(self, base_url: str, token: str, timeout_seconds: float = 10):
        self._base_url = base_url.rstrip("/")
        self._token = token
        self._timeout_seconds = timeout_seconds

    def create_request(self, command: AcceptLegacyEvent) -> UUID:
        """使用旧事件标识作为全局幂等键创建请求。"""
        body = json.dumps({
            "idempotencyKey": f"downloadbot:{command.event_id}",
            "sourceType": command.source_type,
            "sourceKey": command.source_key,
            "requestKind": command.request_kind,
            "parameters": command.parameters,
        }, separators=(",", ":")).encode("utf-8")
        request = Request(
            f"{self._base_url}/api/v1/download-requests", data=body, method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json",
                     "Authorization": f"Bearer {self._token}"},
        )
        with urlopen(request, timeout=self._timeout_seconds) as response:
            return UUID(json.loads(response.read().decode("utf-8"))["id"])
