"""旧资产快照适配器内部 HTTP API。"""

from __future__ import annotations

from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
import hmac
import json
from urllib.parse import parse_qs, urlparse


def create_handler(service, internal_token: str) -> type[BaseHTTPRequestHandler]:
    """创建仅提供健康检查和只读导出的处理器。"""

    class Handler(BaseHTTPRequestHandler):
        """处理适配器内部请求。"""

        def do_GET(self) -> None:  # noqa: N802
            """返回健康状态或有界快照页。"""
            parsed = urlparse(self.path)
            if parsed.path == "/health":
                self._json(HTTPStatus.OK, {"status": "UP"})
                return
            if parsed.path != "/internal/v1/migration/assets":
                self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})
                return
            if not self._authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                query = parse_qs(parsed.query, keep_blank_values=True)
                snapshot_id = query.get("snapshotId", [""])[0]
                after_id = query.get("afterId", [None])[0]
                limit = int(query.get("limit", ["200"])[0])
                self._json(HTTPStatus.OK, service.page(snapshot_id, after_id, limit))
            except PermissionError as exception:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exception)})
            except LookupError as exception:
                self._json(HTTPStatus.NOT_FOUND, {"error": str(exception)})
            except (TypeError, ValueError) as exception:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})

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
            """关闭可能包含迁移游标的默认访问日志。"""

    return Handler
