"""OneBot Connector 自有模型。"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID, uuid4


@dataclass(frozen=True, slots=True)
class Account:
    """不包含已解析凭据材料的服务端 OneBot 账户路由。"""

    id: UUID
    external_key: str
    http_base_url: str
    secret_ref: str
    host_qq_root: str
    container_qq_root: str
    enabled: bool
    created_at: datetime
    updated_at: datetime

    @classmethod
    def create(cls, external_key: str, http_base_url: str, secret_ref: str,
               host_qq_root: str, container_qq_root: str, enabled: bool) -> "Account":
        """在边界校验完成后创建账户聚合。"""
        now = datetime.now(UTC)
        return cls(uuid4(), external_key, http_base_url, secret_ref, host_qq_root,
                   container_qq_root, enabled, now, now)


@dataclass(frozen=True, slots=True)
class ProviderFileRequest:
    """从 Messaging 接收的标准化渠道文件请求。"""

    account_key: str
    attachment_type: str
    provider_file_id: str


@dataclass(slots=True)
class ContentSource:
    """仅在单次请求内持有的已准备本地或 HTTP 内容源。"""

    account: Account
    local_path: str | None = None
    url: str | None = None
