#!/usr/bin/env python3
"""触发 OneBot 重登录并等待新二维码可用。"""
from __future__ import annotations

from datetime import datetime
import json
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from mytools_task_sdk.context import TaskContext

SAFE_KEY = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
RELOGIN_WAIT_SECONDS = 150.0
RELOGIN_STATE_PROBE_INTERVAL = 4


class ConnectorClient:
    """OneBot Connector 固定控制接口客户端。"""

    def __init__(self, base_url: str, token: str, timeout: float = 5) -> None:
        if timeout <= 0:
            raise ValueError("OneBot Connector timeout must be positive")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def request_relogin(self, account_key: str, request_id: str,
                        timeout: float | None = None) -> dict:
        """提交一次幂等重登录请求。"""
        return self._json("/internal/v1/control/relogin",
                          {"accountKey": account_key, "requestId": request_id}, timeout)

    def qr_ready(self, account_key: str, requested_at: str,
                 timeout: float | None = None) -> bool:
        """只判断新鲜二维码是否已通过 Connector 校验。"""
        request = self._request("/internal/v1/control/login-qr/content",
                                {"accountKey": account_key, "requestedAt": requested_at})
        try:
            with urlopen(request, timeout=self._timeout(timeout)) as response:
                content_type = response.headers.get_content_type()
                content_length = int(response.headers.get("Content-Length", "0"))
                prefix = response.read(8)
                return response.status == 200 and content_type == "image/png" \
                    and 8 <= content_length <= 2 * 1024 * 1024 \
                    and prefix == b"\x89PNG\r\n\x1a\n"
        except HTTPError as exception:
            if exception.code in {502, 503}:
                return False
            raise

    def _json(self, path: str, payload: dict, timeout: float | None = None) -> dict:
        with urlopen(self._request(path, payload), timeout=self._timeout(timeout)) as response:
            result = json.loads(response.read().decode("utf-8"))
        if not isinstance(result, dict):
            raise RuntimeError("OneBot Connector returned an invalid response")
        return result

    def _request(self, path: str, payload: dict) -> Request:
        return Request(self.base_url + path,
                       data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
                       method="POST", headers={"Authorization": f"Bearer {self.token}",
                                                "Content-Type": "application/json"})

    def _timeout(self, requested: float | None) -> float:
        if requested is None:
            return self.timeout
        if requested <= 0:
            raise ValueError("OneBot Connector timeout must be positive")
        return min(self.timeout, requested)


def execute(context: TaskContext, client: ConnectorClient, sleeper=time.sleep,
            monotonic=time.monotonic) -> dict:
    """触发重登录并在任务超时边界内等待二维码。"""
    account_key = str(context.parameters["accountKey"])
    request_id = str(context.parameters["requestId"])
    if not SAFE_KEY.fullmatch(account_key) or not SAFE_KEY.fullmatch(request_id):
        raise ValueError("OneBot relogin parameters are invalid")
    deadline = monotonic() + RELOGIN_WAIT_SECONDS
    response = client.request_relogin(
        account_key, request_id, timeout=_remaining_timeout(deadline, monotonic))
    status, requested_at = _validated_relogin_response(response)
    if status == "ALREADY_ONLINE":
        return {"accountKey": account_key, "requestId": request_id,
                "status": "ALREADY_ONLINE"}
    if status == "FAILED":
        raise RuntimeError("OneBot relogin action retry limit is exhausted")
    for probe in range(240):
        remaining = _remaining_timeout(deadline, monotonic)
        if client.qr_ready(account_key, requested_at, timeout=remaining):
            return {"accountKey": account_key, "requestId": request_id,
                    "requestedAt": requested_at, "status": "QR_READY"}
        if (probe + 1) % RELOGIN_STATE_PROBE_INTERVAL == 0:
            response = client.request_relogin(
                account_key, request_id,
                timeout=_remaining_timeout(deadline, monotonic))
            current_status, current_requested_at = _validated_relogin_response(response)
            if current_status == "ALREADY_ONLINE":
                return {"accountKey": account_key, "requestId": request_id,
                        "status": "ALREADY_ONLINE"}
            if current_requested_at != requested_at:
                raise RuntimeError("OneBot relogin receipt changed during execution")
            if current_status == "FAILED":
                raise RuntimeError("OneBot relogin action retry limit is exhausted")
        sleeper(min(0.5, _remaining_timeout(deadline, monotonic)))
    raise RuntimeError("fresh OneBot login QR was not generated before timeout")


def _remaining_timeout(deadline: float, monotonic) -> float:
    remaining = deadline - monotonic()
    if remaining <= 0:
        raise RuntimeError("fresh OneBot login QR was not generated before timeout")
    return remaining


def _validated_relogin_response(response: dict) -> tuple[str, str]:
    if not isinstance(response, dict):
        raise RuntimeError("OneBot Connector returned an invalid relogin response")
    status = str(response.get("status") or "")
    if status == "ALREADY_ONLINE":
        return status, ""
    if status not in {"REQUESTED", "RESTARTED", "FAILED"}:
        raise RuntimeError("OneBot Connector returned an invalid relogin response")
    requested_at = str(response.get("requestedAt") or "")
    try:
        parsed = datetime.fromisoformat(requested_at.replace("Z", "+00:00"))
    except ValueError as exception:
        raise RuntimeError("OneBot Connector returned an invalid relogin response") from exception
    if parsed.tzinfo is None:
        raise RuntimeError("OneBot Connector returned an invalid relogin response")
    return status, requested_at


def write_result(result: dict) -> None:
    """原子写入不包含二维码内容或路径的任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行 OneBot 重登录任务。"""
    context = TaskContext.load()
    client = ConnectorClient(os.getenv("ONEBOT_CONNECTOR_URL", "http://127.0.0.1:23255"),
                             os.environ["ONEBOT_CONNECTOR_INTERNAL_TOKEN"])
    write_result(execute(context, client))


if __name__ == "__main__":
    main()
