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
    assert "https://x.com/example" == calls[0][-1]


def test_creates_post_tasks_in_twenty_item_batches(monkeypatch):
    """四十五条帖子按二十条一批创建并等待三批。"""
    waited = []
    monkeypatch.setattr(MODULE, "wait_all_or_cancel",
                        lambda _context, children, _timeout: waited.append(len(children)))
    context = Context()

    result = MODULE.execute(context, "example", [str(1000 + index) for index in range(45)])

    assert result == {"requestId": "11111111-1111-4111-8111-111111111111",
                      "username": "example", "postCount": 45, "batchCount": 3}
    assert waited == [20, 20, 5]
    assert {task_name for _, task_name, _ in context.children} == {"download_x_post"}
    assert {parameters["albumFolder"] for _, _, parameters in context.children} == {"example"}
    assert [context.children[index][2]["sourceIndexOffset"] for index in (0, 1, 44)] \
        == [0, 100, 4400]


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
