"""适配器内部 HTTP API。"""

from __future__ import annotations

from dataclasses import asdict
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
import hmac
import json
from urllib.parse import parse_qs, urlparse
from uuid import UUID

from .models import AcceptLegacyEvent
from .service import AdapterService


def create_handler(service: AdapterService, internal_token: str, snapshot_repository=None,
                   snapshot_export_enabled: bool = False,
                   snapshot_export_token: str | None = None,
                   reconciliation_enabled: bool = False, pikpak_exporter=None,
                   pikpak_export_enabled: bool = False,
                   pikpak_export_token: str | None = None) -> type[BaseHTTPRequestHandler]:
    """创建绑定应用依赖的 HTTP 处理器。"""

    class Handler(BaseHTTPRequestHandler):
        """提供健康检查和旧事件接入。"""

        def do_GET(self) -> None:  # noqa: N802
            """处理健康检查。"""
            parsed = urlparse(self.path)
            if parsed.path == "/health":
                self._json(HTTPStatus.OK, {"status": "UP"})
                return
            if parsed.path == "/internal/v1/migration/downloadbot/snapshot-items":
                if not self._authorized(snapshot_export_token or ""):
                    self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                if not snapshot_export_enabled or snapshot_repository is None:
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE,
                               {"error": "snapshot export is disabled"})
                    return
                try:
                    query = parse_qs(parsed.query, keep_blank_values=True)
                    snapshot_id = UUID(query.get("snapshotId", [""])[0])
                    after_id = query.get("afterId", [None])[0]
                    limit = int(query.get("limit", ["200"])[0])
                    self._json(HTTPStatus.OK, snapshot_repository.export_page(
                        snapshot_id, after_id, limit))
                except LookupError as exception:
                    self._json(HTTPStatus.NOT_FOUND, {"error": str(exception)})
                except PermissionError as exception:
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exception)})
                except (TypeError, ValueError) as exception:
                    self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})
                return
            if parsed.path == "/internal/v1/migration/downloadbot/pikpak-accounts":
                if not self._authorized(pikpak_export_token or ""):
                    self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                if not pikpak_export_enabled or pikpak_exporter is None:
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE,
                               {"error": "PikPak config export is disabled"})
                    return
                try:
                    query = parse_qs(parsed.query, keep_blank_values=True)
                    after_id = query.get("afterId", [None])[0]
                    limit = int(query.get("limit", ["50"])[0])
                    self._json(HTTPStatus.OK, pikpak_exporter.export_page(after_id, limit))
                except (OSError, TypeError, ValueError) as exception:
                    self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})
                return
            reconciliation_prefix = "/internal/v1/reconciliation/downloadbot/events/"
            if parsed.path.startswith(reconciliation_prefix):
                if not self._authorized(snapshot_export_token or ""):
                    self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                if not reconciliation_enabled or snapshot_repository is None:
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE,
                               {"error": "download reconciliation is disabled"})
                    return
                try:
                    query = parse_qs(parsed.query, keep_blank_values=True)
                    snapshot_id = UUID(query.get("snapshotId", [""])[0])
                    event_id = parsed.path.removeprefix(reconciliation_prefix)
                    if not event_id or len(event_id) > 255:
                        raise ValueError("event id is invalid")
                    self._json(HTTPStatus.OK, snapshot_repository.reconciliation_evidence(
                        snapshot_id, event_id))
                except LookupError as exception:
                    self._json(HTTPStatus.NOT_FOUND, {"error": str(exception)})
                except PermissionError as exception:
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exception)})
                except (TypeError, ValueError) as exception:
                    self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})
                return
            self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})

        def do_POST(self) -> None:  # noqa: N802
            """幂等接受一个旧下载请求事件。"""
            if not self._authorized(internal_token):
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

        def _authorized(self, token: str) -> bool:
            return bool(token) and hmac.compare_digest(
                self.headers.get("Authorization", ""), f"Bearer {token}")

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
