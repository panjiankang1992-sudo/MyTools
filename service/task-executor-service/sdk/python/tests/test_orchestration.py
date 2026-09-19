"""子任务编排 SDK 测试。"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from unittest.mock import patch

import pytest

from mytools_task_sdk.context import TaskInstance
from mytools_task_sdk.orchestration import wait_all_or_cancel


def _task(task_id: str, status: str = "RUNNING") -> TaskInstance:
    return TaskInstance(task_id, "child", status, "parent")


@dataclass
class _FakeClock:
    now: float = 100.0

    def monotonic(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


@dataclass
class _FakeContext:
    clock: _FakeClock
    outcomes: dict[str, tuple[str, float] | BaseException]
    statuses: dict[str, str]
    wait_calls: list[tuple[str, float, float]] = field(default_factory=list)
    cancelled: list[str] = field(default_factory=list)

    TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"}

    def wait_child(
        self,
        task_id: str,
        timeout_seconds: float,
        poll_seconds: float = 1.0,
    ) -> TaskInstance:
        self.wait_calls.append((task_id, timeout_seconds, poll_seconds))
        outcome = self.outcomes[task_id]
        if isinstance(outcome, BaseException):
            self.clock.advance(timeout_seconds)
            raise outcome
        status, elapsed = outcome
        self.clock.advance(min(elapsed, timeout_seconds))
        if elapsed > timeout_seconds:
            raise TimeoutError(f"child task {task_id} did not finish before timeout")
        self.statuses[task_id] = status
        return _task(task_id, status)

    def get_task(self, task_id: str) -> TaskInstance:
        return _task(task_id, self.statuses[task_id])

    def cancel_child(self, task_id: str) -> TaskInstance:
        self.cancelled.append(task_id)
        self.statuses[task_id] = "CANCELLED"
        return _task(task_id, "CANCELLED")


def _wait(
    context: _FakeContext,
    children: Iterable[TaskInstance],
    timeout_seconds: float,
) -> list[TaskInstance]:
    with patch(
        "mytools_task_sdk.orchestration.time.monotonic",
        context.clock.monotonic,
    ):
        return wait_all_or_cancel(context, children, timeout_seconds)  # type: ignore[arg-type]


def test_wait_all_returns_successes_with_fast_polling() -> None:
    """全部成功时返回结果，并统一使用较快的轮询间隔。"""
    clock = _FakeClock()
    context = _FakeContext(
        clock,
        {"one": ("SUCCEEDED", 2.0), "two": ("SUCCEEDED", 3.0)},
        {"one": "RUNNING", "two": "RUNNING"},
    )

    completed = _wait(context, [_task("one"), _task("two")], 10.0)

    assert [child.id for child in completed] == ["one", "two"]
    assert context.wait_calls == [("one", 10.0, 0.25), ("two", 8.0, 0.25)]
    assert context.cancelled == []


def test_wait_all_shares_one_timeout_budget_and_cancels_active_children() -> None:
    """后续子任务只获得剩余预算，超时后取消所有仍活跃的子任务。"""
    clock = _FakeClock()
    context = _FakeContext(
        clock,
        {"one": ("SUCCEEDED", 7.0), "two": ("SUCCEEDED", 4.0)},
        {"one": "RUNNING", "two": "RUNNING", "three": "RUNNING"},
    )

    with pytest.raises(TimeoutError):
        _wait(context, [_task("one"), _task("two"), _task("three")], 10.0)

    assert context.wait_calls == [("one", 10.0, 0.25), ("two", 3.0, 0.25)]
    assert clock.now == 110.0
    assert context.cancelled == ["two", "three"]


def test_wait_all_cancels_remaining_children_after_failure() -> None:
    """发现失败终态后不再等待后续子任务，并取消仍活跃的任务。"""
    clock = _FakeClock()
    context = _FakeContext(
        clock,
        {"one": ("FAILED", 1.0), "two": ("SUCCEEDED", 1.0)},
        {"one": "RUNNING", "two": "RUNNING"},
    )

    with pytest.raises(RuntimeError, match="one or more child tasks failed"):
        _wait(context, [_task("one"), _task("two")], 10.0)

    assert [call[0] for call in context.wait_calls] == ["one"]
    assert context.cancelled == ["two"]


def test_wait_all_consumes_generator_once() -> None:
    """失败清理复用已物化列表，不会再次消费输入生成器。"""
    clock = _FakeClock()
    context = _FakeContext(
        clock,
        {"one": ("FAILED", 0.0)},
        {"one": "RUNNING", "two": "RUNNING"},
    )
    yielded: list[str] = []

    def children() -> Iterable[TaskInstance]:
        for task_id in ("one", "two"):
            yielded.append(task_id)
            yield _task(task_id)

    with pytest.raises(RuntimeError, match="one or more child tasks failed"):
        _wait(context, children(), 5.0)

    assert yielded == ["one", "two"]
    assert context.cancelled == ["two"]
