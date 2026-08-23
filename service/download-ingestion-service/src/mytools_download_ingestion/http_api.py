"""Minimal HTTP API for accepting download requests during sidecar migration."""

from __future__ import annotations

from dataclasses import asdict
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
import json
from urllib.parse import urlparse
from uuid import UUID
import hmac

from .models import CreateDownloadRequest, DownloadRequest
from .service import DownloadRequestRepository, DownloadRequestService


def request_document(request: DownloadRequest) -> dict:
    """Serialize one download aggregate to its stable API representation."""
    document = asdict(request)
    for key, value in tuple(document.items()):
        if isinstance(value, (UUID, datetime)):
            document[key] = str(value)
    document["status"] = request.status.value
    document["task_instance_id"] = None if request.task_instance_id is None else str(request.task_instance_id)
    return document


def create_handler(service: DownloadRequestService,
                   repository: DownloadRequestRepository,
                   internal_token: str) -> type[BaseHTTPRequestHandler]:
    """Create a request handler bound to application dependencies."""

    class DownloadRequestHandler(BaseHTTPRequestHandler):
        """Serve health, create, and query endpoints."""

        def do_GET(self) -> None:  # noqa: N802
            """Handle health and request lookup."""
            path = urlparse(self.path).path
            if path == "/health":
                self._json(HTTPStatus.OK, {"status": "UP"})
                return
            if not self._authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            prefix = "/api/v1/download-requests/"
            if path.startswith(prefix):
                summary_suffix = "/result-summary"
                identifier = path.removeprefix(prefix)
                if identifier.endswith(summary_suffix):
                    try:
                        summary = service.result_summary(UUID(
                            identifier.removesuffix(summary_suffix).rstrip("/")
                        ))
                    except ValueError:
                        self._json(HTTPStatus.BAD_REQUEST, {"error": "invalid request id"})
                        return
                    if summary is None:
                        self._json(HTTPStatus.NOT_FOUND, {"error": "download request does not exist"})
                        return
                    self._json(HTTPStatus.OK, summary)
                    return
                try:
                    request = service.get(UUID(path.removeprefix(prefix)))
                except ValueError:
                    self._json(HTTPStatus.BAD_REQUEST, {"error": "invalid request id"})
                    return
                if request is None:
                    self._json(HTTPStatus.NOT_FOUND, {"error": "download request does not exist"})
                    return
                self._json(HTTPStatus.OK, request_document(request))
                return
            self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})

        def do_POST(self) -> None:  # noqa: N802
            """Accept one idempotent download request."""
            if not self._authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            path = urlparse(self.path).path
            prefix = "/api/v1/download-requests/"
            internal_prefix = "/internal/v1/download-requests/"
            if path.startswith(internal_prefix) and path.endswith("/result"):
                identifier = path.removeprefix(internal_prefix).removesuffix("/result").rstrip("/")
                try:
                    result = service.record_result(UUID(identifier), self._read_json())
                except KeyError as exception:
                    self._json(HTTPStatus.NOT_FOUND, {"error": str(exception)})
                    return
                except (TypeError, ValueError, json.JSONDecodeError) as exception:
                    self._json(HTTPStatus.CONFLICT, {"error": str(exception)})
                    return
                self._json(HTTPStatus.OK, result)
                return
            if path.startswith(prefix) and path.endswith("/cancel"):
                identifier = path.removeprefix(prefix).removesuffix("/cancel").rstrip("/")
                try:
                    result = service.cancel(UUID(identifier))
                except ValueError:
                    self._json(HTTPStatus.BAD_REQUEST, {"error": "invalid request id"})
                    return
                except Exception:
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "download orchestration unavailable"})
                    return
                if result is None:
                    self._json(HTTPStatus.NOT_FOUND, {"error": "download request does not exist"})
                    return
                self._json(HTTPStatus.OK, request_document(result))
                return
            if path != "/api/v1/download-requests":
                self._json(HTTPStatus.NOT_FOUND, {"error": "route does not exist"})
                return
            try:
                payload = self._read_json()
                command = CreateDownloadRequest(
                    idempotency_key=str(payload["idempotencyKey"]),
                    source_type=str(payload["sourceType"]),
                    source_key=str(payload["sourceKey"]),
                    request_kind=str(payload["requestKind"]),
                    parameters=dict(payload.get("parameters") or {}),
                )
                result = service.create(command)
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})
                return
            except Exception:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "download orchestration unavailable"})
                return
            self._json(HTTPStatus.ACCEPTED, request_document(result))

        def _read_json(self) -> dict:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 1024 * 1024:
                raise ValueError("request body size is invalid")
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(payload, dict):
                raise ValueError("request body must be an object")
            return payload

        def _authorized(self) -> bool:
            """Validate the internal bearer token in constant time."""
            if not internal_token:
                return False
            return hmac.compare_digest(self.headers.get("Authorization", ""),
                                       f"Bearer {internal_token}")

        def _json(self, status: HTTPStatus, payload: dict) -> None:
            body = json.dumps(payload, separators=(",", ":"), default=str).encode("utf-8")
            self.send_response(status.value)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: object) -> None:
            """Suppress default stderr request logging in favor of service logging."""

    return DownloadRequestHandler
