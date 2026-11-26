"""Minimal HTTP API for accepting download requests during sidecar migration."""

from __future__ import annotations

from dataclasses import asdict
from datetime import datetime
import hmac
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
import json
import logging
from urllib.parse import parse_qs, urlparse
from uuid import UUID

from .models import CreateDownloadRequest, DownloadRequest
from .migration import LegacyHistoryMigrationService
from .service import DownloadRequestRepository, DownloadRequestService

LOGGER = logging.getLogger(__name__)


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
                   internal_token: str,
                   migration_service: LegacyHistoryMigrationService | None = None,
                   app_tools=None) -> type[BaseHTTPRequestHandler]:
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
            if path.startswith("/internal/v1/app-download-tools/"):
                self._app_tool(path)
                return
            internal_prefix = "/internal/v1/download-requests/"
            if path.startswith(internal_prefix):
                summary_suffix = "/result-summary"
                identifier = path.removeprefix(internal_prefix)
                try:
                    owner_id = self._query_owner()
                    if identifier.endswith(summary_suffix):
                        result = service.result_summary_for_owner(
                            UUID(identifier.removesuffix(summary_suffix).rstrip("/")), owner_id)
                    else:
                        result = service.get_for_owner(UUID(identifier), owner_id)
                except ValueError:
                    self._json(HTTPStatus.BAD_REQUEST, {"error": "invalid owner-bound request"})
                    return
                if result is None:
                    self._json(HTTPStatus.NOT_FOUND, {"error": "download request does not exist"})
                    return
                self._json(HTTPStatus.OK, result if isinstance(result, dict)
                           else request_document(result))
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
            if path.startswith("/internal/v1/app-download-tools/"):
                self._app_tool(path)
                return
            prefix = "/api/v1/download-requests/"
            internal_prefix = "/internal/v1/download-requests/"
            if path == "/internal/v1/migrations/downloadbot-history/batches":
                if migration_service is None:
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE,
                               {"error": "history migration is unavailable"})
                    return
                try:
                    payload = self._read_json()
                    result = migration_service.migrate(
                        str(payload["migrationKey"]), str(payload.get("sourceSystem") or ""),
                        payload["dryRun"], payload["items"])
                except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
                    self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})
                    return
                self._json(HTTPStatus.OK, result)
                return
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
            if path.startswith(internal_prefix) and path.endswith("/progress"):
                identifier = path.removeprefix(internal_prefix).removesuffix("/progress").rstrip("/")
                try:
                    result = service.record_progress(UUID(identifier), self._read_json())
                except KeyError as exception:
                    self._json(HTTPStatus.NOT_FOUND, {"error": str(exception)})
                    return
                except (TypeError, ValueError, json.JSONDecodeError) as exception:
                    self._json(HTTPStatus.CONFLICT, {"error": str(exception)})
                    return
                self._json(HTTPStatus.OK, result)
                return
            if path.startswith(internal_prefix) and path.endswith("/tags"):
                identifier = path.removeprefix(internal_prefix).removesuffix("/tags").rstrip("/")
                try:
                    result = service.record_tags(UUID(identifier), self._read_json())
                except KeyError as exception:
                    self._json(HTTPStatus.NOT_FOUND, {"error": str(exception)})
                    return
                except (TypeError, ValueError, json.JSONDecodeError) as exception:
                    self._json(HTTPStatus.CONFLICT, {"error": str(exception)})
                    return
                self._json(HTTPStatus.OK, result)
                return
            if path.startswith(internal_prefix) and path.endswith("/cancel"):
                identifier = path.removeprefix(internal_prefix).removesuffix("/cancel").rstrip("/")
                try:
                    result = service.cancel_for_owner(UUID(identifier), self._query_owner())
                except ValueError:
                    self._json(HTTPStatus.BAD_REQUEST, {"error": "invalid owner-bound request"})
                    return
                except Exception:
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE,
                               {"error": "download orchestration unavailable"})
                    return
                if result is None:
                    self._json(HTTPStatus.NOT_FOUND, {"error": "download request does not exist"})
                    return
                self._json(HTTPStatus.OK, request_document(result))
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
                parameters = dict(payload.get("parameters") or {})
                owner_id = self._owner_id(payload, parameters)
                command = CreateDownloadRequest(
                    idempotency_key=str(payload["idempotencyKey"]),
                    source_type=str(payload["sourceType"]),
                    source_key=str(payload["sourceKey"]),
                    request_kind=str(payload["requestKind"]),
                    parameters=parameters,
                    owner_id=owner_id,
                )
                result = service.create(command)
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exception)})
                return
            except Exception:
                LOGGER.exception("Download request orchestration failed")
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "download orchestration unavailable"})
                return
            self._json(HTTPStatus.ACCEPTED, request_document(result))

        def do_PUT(self) -> None:  # noqa: N802
            """接收 APP 有界二进制上传块。"""
            if not self._authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            self._app_tool(urlparse(self.path).path)

        def _app_tool(self, path):
            """仅允许可信 Gateway 提供主体，用户无法指定托管文件来源。"""
            if app_tools is None:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error":"tools_unavailable"})
                return
            try:
                import re
                owner=int(self.headers.get('X-Owner-Id','0'))
                username=self.headers.get('X-Resource-Username','')
                if owner<=0 or not re.fullmatch(r'[A-Za-z0-9._-]{1,128}',username):
                    raise ValueError('invalid_principal')
                if not re.fullmatch(r'[A-Za-z0-9_]{1,64}',username):
                    username=f'owner_{owner}'
                route=path.removeprefix('/internal/v1/app-download-tools/').split('/')
                result=None
                if self.command=='GET' and route==['history']:
                    query = parse_qs(urlparse(self.path).query, keep_blank_values=True, max_num_fields=16)
                    if 'limit' in query or 'before' in query:
                        limits, cursors = query.get('limit', []), query.get('before', [])
                        if len(limits) != 1 or not re.fullmatch(r'[1-9][0-9]?', limits[0]) or len(cursors) > 1:
                            raise ValueError('invalid_history_query')
                        result = app_tools.history_page(owner, int(limits[0]), cursors[0] if cursors else None)
                    else:
                        # 未请求分页的旧版 APP 继续接收数组。
                        result = app_tools.history(owner)
                elif self.command=='POST' and route==['messages']:
                    result=app_tools.links(owner,username,self._read_json())
                elif self.command=='POST' and route==['uploads']:
                    result=app_tools.upload(owner,username,self._read_json())
                elif len(route)==2 and route[0]=='messages' and self.command=='GET':
                    result=app_tools.detail(owner,route[1])
                elif len(route)==3 and route[0]=='messages' and route[2]=='cancel' and self.command=='POST':
                    result=app_tools.cancel(owner,route[1])
                elif len(route)==3 and route[0]=='uploads' and route[2]=='complete' and self.command=='POST':
                    result=app_tools.complete(owner,route[1])
                elif len(route)==3 and route[0]=='uploads' and route[2]=='content' and self.command=='PUT':
                    length=int(self.headers.get('Content-Length','0'))
                    if not 0<length<=1048576: raise ValueError('invalid_chunk_length')
                    data=self.rfile.read(length)
                    if len(data)!=length: raise ValueError('truncated_chunk')
                    result=app_tools.chunk(owner,route[1],int(self.headers.get('X-Upload-Offset','-1')),data)
                else:
                    self._json(HTTPStatus.NOT_FOUND, {'error':'route_not_found'})
                    return
                self._json(HTTPStatus.OK,result)
            except KeyError:
                self._json(HTTPStatus.NOT_FOUND, {'error':'not_found'})
            except (ValueError,TypeError):
                self._json(HTTPStatus.BAD_REQUEST, {'error':'invalid_tool_request'})
            except Exception:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {'error':'tool_processing_unavailable'})

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

        @staticmethod
        def _owner_id(payload: dict, parameters: dict) -> int:
            """兼容旧调用并拒绝两个 owner 字段互相冲突。"""
            top_level = payload.get("ownerId")
            nested = parameters.get("ownerId")
            if top_level is not None and nested is not None and top_level != nested:
                raise ValueError("download request owner conflict")
            value = top_level if top_level is not None else nested
            value = 0 if value is None else value
            if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                raise ValueError("download request owner is invalid")
            return value

        def _query_owner(self) -> int:
            """读取严格的单值 owner 查询参数。"""
            values = parse_qs(urlparse(self.path).query, keep_blank_values=True).get("ownerId", [])
            if len(values) != 1 or not values[0].isdigit():
                raise ValueError("download request owner is invalid")
            owner_id = int(values[0])
            if owner_id < 0:
                raise ValueError("download request owner is invalid")
            return owner_id

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
