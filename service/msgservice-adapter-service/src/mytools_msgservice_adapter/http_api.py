"""MsgService 历史迁移适配器内部 HTTP API。"""

from __future__ import annotations

from dataclasses import asdict
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
import hmac
import json
from urllib.parse import parse_qs, urlparse

from .service import SnapshotService


def create_handler(service: SnapshotService, internal_token: str) -> type[BaseHTTPRequestHandler]:
    """创建绑定快照服务的受保护 HTTP 处理器。"""

    class Handler(BaseHTTPRequestHandler):
        """提供健康检查、快照装载和历史导出接口。"""

        def do_GET(self) -> None:  # noqa: N802
            """处理健康检查或历史消息分页导出。"""
            parsed = urlparse(self.path)
            if parsed.path == "/health":
                self._json(HTTPStatus.OK, {"status": "UP"})
                return
            if parsed.path != "/internal/v1/migration/inbound-messages":
                self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})
                return
            if not self._authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                query = parse_qs(parsed.query, keep_blank_values=True)
                limit = int(query.get("limit", ["200"])[0])
                after_id = query.get("afterId", [None])[0]
                self._json(HTTPStatus.OK, service.export_page(after_id, limit))
            except PermissionError as exception:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exception)})
            except (TypeError, ValueError) as exception:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})

        def do_POST(self) -> None:  # noqa: N802
            """幂等装载一批已脱敏历史快照。"""
            if urlparse(self.path).path != "/internal/v1/migration/inbound-messages/snapshots":
                self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})
                return
            if not self._authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                payload = self._read_json()
                items = payload.get("items")
                if not isinstance(items, list):
                    raise ValueError("items is invalid")
                result = service.import_snapshots(items)
                document = asdict(result)
                document["digestSha256"] = document.pop("digest_sha256")
                self._json(HTTPStatus.OK, document)
            except PermissionError as exception:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exception)})
            except (TypeError, ValueError, json.JSONDecodeError) as exception:
                self._json(HTTPStatus.CONFLICT, {"error": str(exception)})

        def _read_json(self) -> dict:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 16 * 1024 * 1024:
                raise ValueError("request body size is invalid")
            value = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(value, dict):
                raise ValueError("request body must be an object")
            return value

        def _authorized(self) -> bool:
            return bool(internal_token) and hmac.compare_digest(
                self.headers.get("Authorization", ""), f"Bearer {internal_token}")

        def _json(self, status: HTTPStatus, payload: dict) -> None:
            body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            self.send_response(status.value)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: object) -> None:
            """关闭可能包含游标的默认访问日志。"""

    return Handler
