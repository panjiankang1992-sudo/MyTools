"""OneBot Connector 内部 HTTP API。"""

from __future__ import annotations

from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
import hmac
import json
from urllib.parse import urlparse

from .service import OneBotConnectorService

MAXIMUM_REQUEST_BYTES = 64 * 1024


def create_handler(service: OneBotConnectorService, internal_token: str,
                   admin_token: str) -> type[BaseHTTPRequestHandler]:
    """创建绑定连接器服务且要求认证的处理器。"""

    class Handler(BaseHTTPRequestHandler):
        """提供健康检查、账户管理、解析和内容流接口。"""

        def do_GET(self) -> None:  # noqa: N802
            """仅提供无需认证的存活检查接口。"""
            if urlparse(self.path).path == "/health":
                self._json(HTTPStatus.OK, {"status": "UP"})
                return
            self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})

        def do_POST(self) -> None:  # noqa: N802
            """使用独立管理凭据路由经过认证的内部操作。"""
            path = urlparse(self.path).path
            if path == "/internal/v1/accounts":
                if not self._authorized(admin_token):
                    self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                self._run_json(HTTPStatus.CREATED, service.register)
                return
            if path == "/internal/v1/provider-files/resolve":
                if not self._authorized(internal_token):
                    self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                self._run_json(HTTPStatus.OK, service.resolve)
                return
            if path == "/internal/v1/provider-files/content":
                if not self._authorized(internal_token):
                    self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                self._stream_content()
                return
            if path == "/internal/v1/control/relogin":
                if not self._authorized(internal_token):
                    self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                self._run_json(HTTPStatus.ACCEPTED, service.request_relogin)
                return
            if path == "/internal/v1/control/login-qr/content":
                if not self._authorized(internal_token):
                    self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                self._stream_qr()
                return
            self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})

        def _run_json(self, success: HTTPStatus, operation) -> None:
            try:
                result = operation(self._read_json())
                self._json(success, result)
            except RuntimeError as exception:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exception)})
            except (OSError, UnicodeError):
                self._json(HTTPStatus.BAD_GATEWAY, {"error": "provider operation failed"})
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})

        def _stream_content(self) -> None:
            try:
                source = service.prepare_content(self._read_json())
            except RuntimeError as exception:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exception)})
                return
            except (OSError, UnicodeError):
                self._json(HTTPStatus.BAD_GATEWAY, {"error": "provider operation failed"})
                return
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})
                return
            self.send_response(HTTPStatus.OK.value)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                service.stream_content(source, self.wfile)
            except (BrokenPipeError, ConnectionError, OSError, RuntimeError, ValueError):
                self.close_connection = True

        def _stream_qr(self) -> None:
            try:
                source, size = service.prepare_qr(self._read_json())
            except RuntimeError as exception:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exception)})
                return
            except (OSError, UnicodeError):
                self._json(HTTPStatus.BAD_GATEWAY, {"error": "provider operation failed"})
                return
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})
                return
            self.send_response(HTTPStatus.OK.value)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                service.stream_qr(source, self.wfile)
            except (BrokenPipeError, ConnectionError, OSError, RuntimeError, ValueError):
                self.close_connection = True

        def _read_json(self) -> dict:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAXIMUM_REQUEST_BYTES:
                raise ValueError("request body size is invalid")
            value = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(value, dict):
                raise ValueError("request body must be an object")
            return value

        def _authorized(self, token: str) -> bool:
            return bool(token) and hmac.compare_digest(
                self.headers.get("Authorization", ""), f"Bearer {token}")

        def _json(self, status: HTTPStatus, payload: dict) -> None:
            body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            self.send_response(status.value)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: object) -> None:
            """关闭标准错误访问日志。"""

    return Handler
