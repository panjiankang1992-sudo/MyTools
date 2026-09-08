"""任务脚本稳定错误分类契约。"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import tempfile

RETRYABLE_CATEGORIES = {"TRANSIENT", "RATE_LIMITED", "RESOURCE_EXHAUSTED", "TIMEOUT"}
PERMANENT_CATEGORIES = {
    "PERMANENT", "VALIDATION", "AUTHENTICATION", "AUTHORIZATION", "NOT_FOUND", "CONFLICT"
}
ERROR_CODE = re.compile(r"^[A-Z][A-Z0-9_]{2,127}$")


def write_task_error(code: str, category: str, message: str | None = None) -> None:
    """原子写入由 Executor 校验的任务错误文档。"""
    normalized_category = category.upper()
    if not ERROR_CODE.fullmatch(code):
        raise ValueError("task error code is invalid")
    if normalized_category not in RETRYABLE_CATEGORIES | PERMANENT_CATEGORIES:
        raise ValueError("task error category is invalid")
    if message is not None and len(message) > 2048:
        raise ValueError("task error message is too long")
    target = Path(os.environ["TASK_ERROR_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    document = {
        "code": code,
        "category": normalized_category,
        "retryable": normalized_category in RETRYABLE_CATEGORIES,
        "message": message,
    }
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(document, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)
