"""X 用户帖子批量编排测试。"""

import importlib.util
import json
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts/main.py"
SPEC = importlib.util.spec_from_file_location("download_resolve_x_user", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Completed:
    """模拟 gallery-dl 结果。"""

    def __init__(self, messages):
        self.returncode = 0
        self.stdout = json.dumps(messages).encode()


class Child:
    """模拟子任务。"""

    def __init__(self, identifier):
        self.id = identifier
        self.status = "SUCCEEDED"


class Context:
    """记录批量创建的帖子任务。"""

    def __init__(self):
        self.parameters = {"downloadRequestId": "11111111-1111-4111-8111-111111111111",
                           "ownerId": 7, "resourceUsername": "user", "receivedAt": "2026-08-28T00:00:00Z"}
        self.children = []

    def create_child(self, task_name, parameters, _key, **_kwargs):
        child = Child(f"child-{len(self.children)}")
        self.children.append((child, task_name, parameters))
        return child

    def get_task(self, identifier):
        return next(c for c, _, _ in self.children if c.id == identifier)

    def cancel_child(self, identifier):
        self.get_task(identifier).status = "CANCELLED"


def test_reads_profile_metadata_and_deduplicates_posts():
    """同一帖子多个媒体只创建一个帖子任务。"""
    calls = []

    def runner(command, **_kwargs):
        calls.append(command)
        return Completed([[3, "https://pbs.twimg.com/a.jpg", {"tweet_id": "101"}],
                          [3, "https://pbs.twimg.com/b.jpg", {"tweet_id": "101"}],
                          [3, "https://pbs.twimg.com/c.jpg", {"tweet_id": "102"}]])

    username, identifiers = MODULE.enumerate_post_ids({"url": "https://x.com/example"}, runner)

    assert username == "example"
    assert identifiers == ["101", "102"]
    assert "https://x.com/example/media" == calls[0][-1]


def test_window_refills_while_first_post_is_still_running(monkeypatch):
    """队头慢任务不阻止新帖子投递，在途数量始终不超过四条。"""
    context = Context()
    create = context.create_child
    maximum = 0
    def tracked_create(*args, **kwargs):
        nonlocal maximum
        child = create(*args, **kwargs)
        child.status = "RUNNING"
        outstanding = [c for c, _, _ in context.children if c.status == "RUNNING"]
        maximum = max(maximum, len(outstanding))
        return child
    context.create_child = tracked_create
    def current(identifier):
        child = next(c for c, _, _ in context.children if c.id == identifier)
        # 第一条直到所有帖子均已投递才完成，其余任务立即完成。
        if identifier != "child-0" or len(context.children) == 45:
            child.status = "SUCCEEDED"
        return child
    context.get_task = current
    monkeypatch.setattr(MODULE.time, "sleep", lambda _seconds: None)
    result = MODULE.execute(context, "example", [str(1000 + index) for index in range(45)])
    assert result["postCount"] == 45 and result["batchCount"] == 3
    assert maximum <= MODULE.CHILD_WINDOW_SIZE
    assert len(context.children) == 45
    assert [context.children[index][2]["sourceIndexOffset"] for index in (0, 1, 44)] == [0, 100, 4400]
    assert {p["albumFolder"] for _, _, p in context.children} == {"example"}


def test_later_failure_cancels_window_without_waiting_for_head():
    """后位帖子失败必须及时取消仍运行的队头和其他在途任务。"""
    import pytest
    context = Context()
    create = context.create_child
    cancelled = []
    def tracked_create(*args, **kwargs):
        child = create(*args, **kwargs)
        child.status = "FAILED" if child.id == "child-2" else "RUNNING"
        return child
    context.create_child = tracked_create
    context.cancel_child = lambda identifier: cancelled.append(identifier)
    with pytest.raises(RuntimeError):
        MODULE.execute(context, "example", [str(1000 + i) for i in range(45)])
    assert len(context.children) == 4
    assert cancelled == ["child-0", "child-1", "child-3"]


def test_partial_submit_failure_cancels_created_tasks():
    """创建中途失败同样补偿取消已经成功创建的子任务。"""
    import pytest
    context = Context()
    create = context.create_child
    cancelled = []
    def failing_create(*args, **kwargs):
        if len(context.children) == 2:
            raise OSError("submit failed")
        child = create(*args, **kwargs)
        child.status = "RUNNING"
        return child
    context.create_child = failing_create
    context.cancel_child = lambda identifier: cancelled.append(identifier)
    with pytest.raises(OSError):
        MODULE.execute(context, "example", ["1000", "1001", "1002"])
    assert cancelled == ["child-0", "child-1"]


def test_claims_derived_post_links_before_creating_tasks(monkeypatch):
    """派生帖子必须先登记，已存在的帖子不会再次进入任务批次。"""
    captured = {}

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, _maximum):
            return b'{"claimedUrls":["https://x.com/i/web/status/102"]}'

    def opener(request, timeout):
        captured.update(json.loads(request.data))
        assert timeout == 30
        return Response()

    monkeypatch.setenv("MESSAGE_AUTOMATION_URL", "http://automation.test")
    monkeypatch.setenv("MESSAGE_AUTOMATION_INTERNAL_TOKEN", "token")
    parameters = {"messageBatchId": "11111111-1111-4111-8111-111111111111",
                  "ownerId": 7, "receivedAt": "2026-08-28T00:00:00Z"}

    claimed = MODULE.claim_post_ids(parameters, ["101", "102"], opener)

    assert claimed == ["102"]
    assert captured["urls"] == ["https://x.com/i/web/status/101",
                                "https://x.com/i/web/status/102"]


def test_full_media_page_with_duplicate_posts_continues():
    """整页媒体来自较少帖子时仍须读取下一页，不以帖子去重数量截断。"""
    calls = []
    def runner(command, **_kwargs):
        calls.append(command)
        if len(calls) == 1:
            return Completed([[3, "https://pbs.twimg.com/a.jpg", {"tweet_id": str(100 + i // 4)}]
                              for i in range(MODULE.PAGE_SIZE)])
        return Completed([[3, "https://pbs.twimg.com/b.jpg", {"tweet_id": "999"}]])
    _, identifiers = MODULE.enumerate_post_ids({"url": "https://x.com/example"}, runner)
    assert len(calls) == 2
    assert identifiers[-1] == "999"
    assert calls[1][calls[1].index("--range") + 1] == "101-200"


def test_app_namespace_does_not_call_message_registry():
    """APP 批次不调用消息登记，帖子列表保持完整。"""
    def forbidden(*_args, **_kwargs):
        raise AssertionError("message registry must not be called")
    assert MODULE.claim_post_ids({"messageBatchId": "app:11111111-1111-4111-8111-111111111111"},
                                 ["101", "102"], forbidden) == ["101", "102"]
