"""独立连接器 schema 的 MySQL 仓储。"""

from __future__ import annotations

from datetime import UTC
from uuid import UUID

from .models import Account


class MySqlAccountRepository:
    """持久化账户且不解析或返回凭据。"""

    def __init__(self, connection_factory) -> None:
        self._connection_factory = connection_factory

    def find_by_external_key(self, external_key: str) -> Account | None:
        """通过稳定外部键返回账户。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT * FROM onebot_account WHERE external_key=%s", (external_key,))
                row = cursor.fetchone()
                return None if row is None else map_account(row)
        finally:
            connection.close()

    def save(self, account: Account) -> Account:
        """通过精确冲突检测幂等插入账户。"""
        existing = self.find_by_external_key(account.external_key)
        if existing is not None:
            comparable = ("http_base_url", "secret_ref", "host_qq_root", "container_qq_root", "enabled")
            if any(getattr(existing, field) != getattr(account, field) for field in comparable):
                raise ValueError("OneBot account idempotency conflict")
            return existing
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("""
                    INSERT INTO onebot_account
                    (id,external_key,http_base_url,secret_ref,host_qq_root,container_qq_root,
                     enabled,created_at,updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
                    """, (str(account.id), account.external_key, account.http_base_url,
                          account.secret_ref, account.host_qq_root, account.container_qq_root,
                          account.enabled, account.created_at, account.updated_at))
            connection.commit()
        finally:
            connection.close()
        return account


def map_account(row: dict) -> Account:
    """将一条字典游标记录映射为领域模型。"""
    return Account(UUID(str(row["id"])), str(row["external_key"]), str(row["http_base_url"]),
                   str(row["secret_ref"]), str(row["host_qq_root"]),
                   str(row["container_qq_root"]), bool(row["enabled"]),
                   row["created_at"].replace(tzinfo=UTC), row["updated_at"].replace(tzinfo=UTC))
