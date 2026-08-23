"""OneBot Connector 应用边界。"""

from __future__ import annotations

from pathlib import Path, PurePosixPath
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
                 enabled: bool, maximum_bytes: int) -> None:
        if maximum_bytes <= 0:
            raise ValueError("OneBot Connector maximumBytes must be positive")
        self._repository = repository
        self._client = client
        self._enabled = enabled
        self._maximum_bytes = maximum_bytes

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
