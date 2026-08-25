"""OneBot Connector 应用边界。"""

from __future__ import annotations

from pathlib import Path, PurePosixPath
from datetime import UTC, datetime
import json
import os
import re
from urllib.parse import urlparse

from .connector import OneBotClient
from .models import Account, ProviderFileRequest
from .repository import AccountRepository

SAFE_KEY = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
ATTACHMENT_TYPES = {"IMAGE", "VIDEO", "RECORD", "FILE"}


class OneBotConnectorService:
    """管理账户路由并隔离渠道凭据操作。"""

    def __init__(self, repository: AccountRepository, client: OneBotClient,
                 enabled: bool, maximum_bytes: int,
                 relogin_request_path: str | None = None,
                 qr_path: str | None = None,
                 maximum_qr_bytes: int = 2 * 1024 * 1024) -> None:
        if maximum_bytes <= 0:
            raise ValueError("OneBot Connector maximumBytes must be positive")
        self._repository = repository
        self._client = client
        self._enabled = enabled
        self._maximum_bytes = maximum_bytes
        self._relogin_request_path = Path(relogin_request_path) if relogin_request_path else None
        self._qr_path = Path(qr_path) if qr_path else None
        self._maximum_qr_bytes = maximum_qr_bytes
        if maximum_qr_bytes <= 0:
            raise ValueError("OneBot Connector maximumQrBytes must be positive")

    def register(self, payload: dict) -> dict:
        """注册仅限回环地址的账户且不返回凭据引用。"""
        account = validated_account(payload)
        saved = self._repository.save(account)
        return {"id": str(saved.id), "externalKey": saved.external_key, "enabled": saved.enabled}

    def resolve(self, payload: dict) -> dict:
        """将文件解析为 PUBLIC_URL 或 STREAM 模式。"""
        request, account = self._authorized_request(payload)
        source = self._client.prepare(account, request.provider_file_id)
        public = self._client.public_url(source)
        return {"mode": "PUBLIC_URL", "downloadUrl": public} if public else {"mode": "STREAM"}

    def prepare_content(self, payload: dict):
        """准备 STREAM 内容源并拒绝误用公开模式。"""
        request, account = self._authorized_request(payload)
        source = self._client.prepare(account, request.provider_file_id)
        if self._client.public_url(source) is not None:
            raise ValueError("public provider content must use PUBLIC_URL mode")
        return source

    def stream_content(self, source, output) -> int:
        """在连接器配置上限内传输已准备的内容。"""
        return self._client.stream(source, output, self._maximum_bytes)

    def request_relogin(self, payload: dict) -> dict:
        """写入固定重登录请求文件并返回可审计的请求时间。"""
        if set(payload) != {"accountKey", "requestId"}:
            raise ValueError("OneBot relogin request is invalid")
        account = self._control_account(payload)
        request_id = str(payload.get("requestId", ""))
        if not SAFE_KEY.fullmatch(request_id):
            raise ValueError("OneBot relogin request id is invalid")
        if self._relogin_request_path is None:
            raise RuntimeError("OneBot relogin control is not configured")
        requested_at = datetime.now(UTC)
        target = self._relogin_request_path
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(f".{target.name}.{os.getpid()}.tmp")
        document = {"accountId": account.external_key, "requestId": request_id,
                    "requestedAt": requested_at.isoformat()}
        temporary.write_text(json.dumps(document, separators=(",", ":")), encoding="utf-8")
        temporary.chmod(0o600)
        os.replace(temporary, target)
        return {"accountKey": account.external_key, "requestId": request_id,
                "requestedAt": requested_at.isoformat(), "status": "REQUESTED"}

    def prepare_qr(self, payload: dict) -> tuple[Path, int]:
        """校验并准备固定二维码文件，不接受调用方提供路径。"""
        self._control_account(payload)
        if set(payload) != {"accountKey", "requestedAt"}:
            raise ValueError("OneBot QR request is invalid")
        try:
            requested_at = datetime.fromisoformat(str(payload["requestedAt"]).replace("Z", "+00:00"))
        except ValueError as exception:
            raise ValueError("OneBot QR request time is invalid") from exception
        if requested_at.tzinfo is None:
            raise ValueError("OneBot QR request time is invalid")
        if self._qr_path is None:
            raise RuntimeError("OneBot QR control is not configured")
        path = self._qr_path.resolve(strict=True)
        stat = path.stat()
        generated_at = datetime.fromtimestamp(stat.st_mtime, UTC)
        if generated_at < requested_at.astimezone(UTC):
            raise RuntimeError("fresh OneBot login QR is not available")
        if stat.st_size <= 0 or stat.st_size > self._maximum_qr_bytes:
            raise ValueError("OneBot login QR size is invalid")
        with path.open("rb") as source:
            if source.read(8) != b"\x89PNG\r\n\x1a\n":
                raise ValueError("OneBot login QR format is invalid")
        return path, stat.st_size

    def stream_qr(self, source: Path, output) -> int:
        """在二维码专用上限内传输固定 PNG 文件。"""
        total = 0
        with source.open("rb") as input_stream:
            while chunk := input_stream.read(64 * 1024):
                total += len(chunk)
                if total > self._maximum_qr_bytes:
                    raise ValueError("OneBot login QR exceeds configured maximum")
                output.write(chunk)
        return total

    def _control_account(self, payload: dict) -> Account:
        if not self._enabled:
            raise RuntimeError("OneBot Connector is disabled")
        account_key = str(payload.get("accountKey", ""))
        if not SAFE_KEY.fullmatch(account_key):
            raise ValueError("OneBot control account is invalid")
        account = self._repository.find_by_external_key(account_key)
        if account is None or not account.enabled:
            raise ValueError("OneBot account is unavailable")
        return account

    def _authorized_request(self, payload: dict) -> tuple[ProviderFileRequest, Account]:
        if not self._enabled:
            raise RuntimeError("OneBot Connector is disabled")
        if set(payload) != {"channelType", "accountKey", "attachmentType", "providerFileId"} \
                or payload.get("channelType") != "ONEBOT":
            raise ValueError("provider file request is invalid")
        request = ProviderFileRequest(str(payload["accountKey"]), str(payload["attachmentType"]),
                                      str(payload["providerFileId"]))
        if not SAFE_KEY.fullmatch(request.account_key) or request.attachment_type not in ATTACHMENT_TYPES \
                or not request.provider_file_id or len(request.provider_file_id) > 512:
            raise ValueError("provider file request is invalid")
        account = self._repository.find_by_external_key(request.account_key)
        if account is None or not account.enabled:
            raise ValueError("OneBot account is unavailable")
        return request, account


def validated_account(payload: dict) -> Account:
    """校验账户路由字段并强制使用明确的安全值。"""
    required = {"externalKey", "httpBaseUrl", "secretRef", "hostQqRoot", "containerQqRoot", "enabled"}
    if set(payload) != required:
        raise ValueError("OneBot account fields are invalid")
    external_key = str(payload["externalKey"])
    base_url = str(payload["httpBaseUrl"]).rstrip("/")
    secret_ref = str(payload["secretRef"])
    host_root = str(payload["hostQqRoot"])
    container_root = str(payload["containerQqRoot"])
    parsed = urlparse(base_url)
    if not SAFE_KEY.fullmatch(external_key) or parsed.scheme not in {"http", "https"} \
            or parsed.hostname not in {"127.0.0.1", "::1", "localhost"} \
            or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("OneBot account route is invalid")
    if not re.fullmatch(r"env://[A-Z][A-Z0-9_]{0,127}", secret_ref):
        raise ValueError("OneBot account secret reference is invalid")
    if not isinstance(payload["enabled"], bool):
        raise ValueError("OneBot account enabled flag is invalid")
    if not Path(host_root).is_absolute() or not PurePosixPath(container_root).is_absolute() \
            or len(host_root) > 1024 or len(container_root) > 1024:
        raise ValueError("OneBot account path mapping is invalid")
    return Account.create(external_key, base_url, secret_ref, host_root, container_root,
                          payload["enabled"])
