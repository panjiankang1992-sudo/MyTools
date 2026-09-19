"""任务脚本共享的子任务编排原语。"""

from __future__ import annotations

import time
from collections.abc import Iterable

from .context import TaskContext, TaskInstance


def wait_all_or_cancel(context: TaskContext, children: Iterable[TaskInstance],
                       timeout_seconds: float) -> list[TaskInstance]:
    """等待全部直接子任务成功，异常或非成功终态时取消其余活跃子任务。"""
    values = list(children)
    deadline = time.monotonic() + max(timeout_seconds, 0.0)
    try:
        completed: list[TaskInstance] = []
        for child in values:
            # 所有子任务共享同一个超时截止点，避免按子任务数量重复消耗完整等待预算。
            remaining_seconds = max(deadline - time.monotonic(), 0.0)
            current = context.wait_child(
                child.id,
                remaining_seconds,
                poll_seconds=0.25,
            )
            completed.append(current)
            # 发现失败终态后立即停止等待，并取消其余仍活跃的子任务。
            if current.status != "SUCCEEDED":
                raise RuntimeError("one or more child tasks failed")
        return completed
    except Exception:
        for child in values:
            current = context.get_task(child.id)
            if current.status not in context.TERMINAL_STATUSES:
                context.cancel_child(child.id)
        raise
