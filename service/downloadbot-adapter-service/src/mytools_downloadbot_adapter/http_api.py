"""适配器内部 HTTP API。"""

from __future__ import annotations

from dataclasses import asdict
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
import hmac
import json
from urllib.parse import urlparse

from .models import AcceptLegacyEvent
from .service import AdapterService


def create_handler(service: AdapterService, internal_token: str) -> type[BaseHTTPRequestHandler]:
    """创建绑定应用依赖的 HTTP 处理器。"""

    class Handler(BaseHTTPRequestHandler):
        """提供健康检查和旧事件接入。"""

        def do_GET(self) -> None:  # noqa: N802
            """处理健康检查。"""
            if urlparse(self.path).path == "/health":
                self._json(HTTPStatus.OK, {"status": "UP"})
                return
            self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})

        def do_POST(self) -> None:  # noqa: N802
            """幂等接受一个旧下载请求事件。"""
            if not self._authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            if urlparse(self.path).path != "/internal/v1/downloadbot/events":
                self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})
                return
            try:
                payload = self._read_json()
                event = service.accept(AcceptLegacyEvent(
                    event_id=str(payload["eventId"]), source_type=str(payload["sourceType"]),
                    source_key=str(payload["sourceKey"]), request_kind=str(payload["requestKind"]),
                    parameters=dict(payload.get("parameters") or {})))
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
                self._json(HTTPStatus.CONFLICT, {"error": str(exception)})
                return
            document = asdict(event)
            document["id"] = str(event.id)
            document["status"] = event.status.value
            document["download_request_id"] = (None if event.download_request_id is None
                                                else str(event.download_request_id))
            self._json(HTTPStatus.ACCEPTED, document)

        def _read_json(self) -> dict:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 1024 * 1024:
                raise ValueError("request body size is invalid")
            value = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(value, dict):
                raise ValueError("request body must be an object")
            return value

        def _authorized(self) -> bool:
            return bool(internal_token) and hmac.compare_digest(
                self.headers.get("Authorization", ""), f"Bearer {internal_token}")

        def _json(self, status: HTTPStatus, payload: dict) -> None:
            body = json.dumps(payload, separators=(",", ":"), default=str).encode("utf-8")
            self.send_response(status.value)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: object) -> None:
            """关闭标准错误输出日志。"""

    return Handler
