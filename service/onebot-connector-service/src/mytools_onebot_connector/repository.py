"""仓储契约和确定性的内存实现。"""

from __future__ import annotations

from typing import Protocol

from .models import Account


class AccountRepository(Protocol):
    """连接器所需的持久化操作。"""

    def find_by_external_key(self, external_key: str) -> Account | None:
        """通过稳定键返回一个账户。"""

    def save(self, account: Account) -> Account:
        """插入账户或返回等价的已有账户。"""


class InMemoryAccountRepository:
    """供契约测试使用的内存仓储。"""

    def __init__(self) -> None:
        self._accounts: dict[str, Account] = {}

    def find_by_external_key(self, external_key: str) -> Account | None:
        """通过稳定键返回一个账户。"""
        return self._accounts.get(external_key)

    def save(self, account: Account) -> Account:
        """幂等保存等价账户并拒绝冲突。"""
        existing = self._accounts.get(account.external_key)
        if existing is not None:
            comparable = ("http_base_url", "secret_ref", "host_qq_root", "container_qq_root", "enabled")
            if any(getattr(existing, field) != getattr(account, field) for field in comparable):
                raise ValueError("OneBot account idempotency conflict")
            return existing
        self._accounts[account.external_key] = account
        return account
