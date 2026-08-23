"""封存旧资产快照分页服务。"""

from __future__ import annotations

import base64
import re
from typing import Any

SNAPSHOT_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


class ExportService:
    """提供默认关闭且有界的 SEALED 快照导出。"""

    def __init__(self, repository, enabled: bool):
        self._repository = repository
        self._enabled = enabled

    def page(self, snapshot_id: str, after_id: str | None, limit: int) -> dict[str, Any]:
        """导出指定冻结快照的一页标准资产载荷。"""
        if not self._enabled:
            raise PermissionError("legacy asset export is disabled")
        if not SNAPSHOT_ID.fullmatch(snapshot_id):
            raise ValueError("snapshotId is invalid")
        if limit < 1 or limit > 200:
            raise ValueError("limit is invalid")
        after_sequence = decode_cursor(after_id)
        values = self._repository.page(snapshot_id, after_sequence, limit + 1)
        page = values[:limit]
        next_after_id = encode_cursor(page[-1]["sequenceId"]) if len(values) > limit and page else None
        return {"snapshotId": snapshot_id, "items": [item["payload"] for item in page],
                "nextAfterId": next_after_id}


def encode_cursor(sequence_id: int) -> str:
    """编码不透明适配器游标。"""
    return base64.urlsafe_b64encode(str(sequence_id).encode()).decode().rstrip("=")


def decode_cursor(value: str | None) -> int:
    """解码并校验适配器游标。"""
    if value is None:
        return 0
    try:
        padding = "=" * (-len(value) % 4)
        sequence = int(base64.urlsafe_b64decode(value + padding).decode())
    except (ValueError, UnicodeDecodeError) as exception:
        raise ValueError("afterId is invalid") from exception
    if sequence < 0:
        raise ValueError("afterId is invalid")
    return sequence
