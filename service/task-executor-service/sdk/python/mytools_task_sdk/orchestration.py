"""任务脚本共享的子任务编排原语。"""

from __future__ import annotations

from collections.abc import Iterable

from .context import TaskContext, TaskInstance


def wait_all_or_cancel(context: TaskContext, children: Iterable[TaskInstance],
                       timeout_seconds: float) -> list[TaskInstance]:
    """等待全部直接子任务成功，异常或非成功终态时取消其余活跃子任务。"""
    values = list(children)
    try:
        completed = [context.wait_child(child.id, timeout_seconds) for child in values]
        if any(child.status != "SUCCEEDED" for child in completed):
            raise RuntimeError("one or more child tasks failed")
        return completed
    except Exception:
        for child in values:
            current = context.get_task(child.id)
            if current.status not in context.TERMINAL_STATUSES:
                context.cancel_child(child.id)
        raise
