"""OneBot Connector 内部 HTTP API。"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
import hmac
import json
from typing import Any
from urllib.parse import urlparse

from .service import OneBotConnectorService

MAXIMUM_REQUEST_BYTES = 64 * 1024
READINESS_FIELDS = frozenset({
    "status",
    "inboundEnabled",
    "startupGrace",
    "bridgeRunning",
    "consumerConnected",
    "consumerReady",
    "consumerDisconnectedAgeSeconds",
    "consumerLastFrameAgeSeconds",
    "walWorkerReady",
    "walWorkerLastSuccessAgeSeconds",
    "deliveryWorkerReady",
    "deliveryWorkerLastSuccessAgeSeconds",
    "walPendingEvents",
    "rejectedInboundEvents",
    "sessionRejectedInboundEvents",
    "websocketOversizedDisconnects",
    "walDeadEvents",
    "databasePendingInboundEvents",
    "databaseDeadInboundEvents",
    "pendingTextReplies",
    "failedTextReplies",
    "uncertainTextReplies",
    "healthProbeReady",
    "healthProbeErrorCode",
    "fatalErrorCode",
})


def create_handler(service: OneBotConnectorService, internal_token: str,
                   admin_token: str,
                   health_provider: Callable[[], Mapping[str, Any]] | None = None
                   ) -> type[BaseHTTPRequestHandler]:
    """创建绑定连接器服务且要求认证的处理器。"""

    readiness_provider = health_provider or (
        lambda: {"status": "UP", "inboundEnabled": False})

    class Handler(BaseHTTPRequestHandler):
        """提供健康检查、账户管理、解析和内容流接口。"""

        def do_GET(self) -> None:  # noqa: N802
            """提供无需认证且不泄露载荷的存活与就绪检查。"""
            path = urlparse(self.path).path
            if path == "/health/live":
                self._json(HTTPStatus.OK, {"status": "UP"})
                return
            if path == "/health":
                self._readiness()
                return
            self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})

        def _readiness(self) -> None:
            """只返回白名单内的安全状态字段。"""
            try:
                provided = readiness_provider()
                if not isinstance(provided, Mapping) \
                        or provided.get("status") not in {"UP", "DOWN"}:
                    raise ValueError("OneBot readiness snapshot is invalid")
                # 健康提供者不得借此接口输出渠道事件、凭据或下游响应正文。
                payload = {key: value for key, value in provided.items()
                           if key in READINESS_FIELDS}
                status = HTTPStatus.OK if payload.get("status") == "UP" \
                    else HTTPStatus.SERVICE_UNAVAILABLE
            except Exception as exception:  # noqa: BLE001
                status = HTTPStatus.SERVICE_UNAVAILABLE
                payload = {
                    "status": "DOWN",
                    "healthProbeReady": False,
                    "healthProbeErrorCode": type(exception).__name__[:128],
                }
            self._json(status, payload)

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
            if path == "/internal/v1/messages/text":
                if not self._authorized(internal_token):
                    self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                self._run_json(HTTPStatus.OK, service.send_text)
                return
            if path == "/internal/v1/messages/forward/expand":
                if not self._authorized(internal_token):
                    self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                self._run_json(HTTPStatus.OK, service.expand_forward)
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
            transfer_encoding = self.headers.get("Transfer-Encoding", "").strip().lower()
            if transfer_encoding:
                if transfer_encoding != "chunked":
                    raise ValueError("request transfer encoding is invalid")
                body = self._read_chunked_body()
            else:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > MAXIMUM_REQUEST_BYTES:
                    raise ValueError("request body size is invalid")
                body = self.rfile.read(length)
                if len(body) != length:
                    raise ValueError("request body is incomplete")
            value = json.loads(body.decode("utf-8"))
            if not isinstance(value, dict):
                raise ValueError("request body must be an object")
            return value

        def _read_chunked_body(self) -> bytes:
            body = bytearray()
            while True:
                line = self.rfile.readline(128)
                if not line.endswith(b"\r\n") or len(line) >= 128:
                    raise ValueError("request chunk header is invalid")
                try:
                    size = int(line[:-2].split(b";", 1)[0], 16)
                except ValueError as exception:
                    raise ValueError("request chunk header is invalid") from exception
                if size == 0:
                    trailer = self.rfile.readline(128)
                    while trailer != b"\r\n":
                        if not trailer.endswith(b"\r\n") or len(trailer) >= 128:
                            raise ValueError("request chunk trailer is invalid")
                        trailer = self.rfile.readline(128)
                    break
                if size < 0 or len(body) + size > MAXIMUM_REQUEST_BYTES:
                    raise ValueError("request body size is invalid")
                chunk = self.rfile.read(size)
                if len(chunk) != size or self.rfile.read(2) != b"\r\n":
                    raise ValueError("request chunk is incomplete")
                body.extend(chunk)
            if not body:
                raise ValueError("request body size is invalid")
            return bytes(body)

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
