"""消息 URL 批次编排测试。"""

import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts/main.py"
SPEC = importlib.util.spec_from_file_location("download_message_url_batch", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Child:
    """测试子任务。"""

    def __init__(self, identifier, task_name):
        self.id = identifier
        self.task_name = task_name
        self.status = "SUCCEEDED"


class Context:
    """记录解析和下载子任务的测试上下文。"""

    def __init__(self, items, counts):
        self.parameters = {"downloadRequestId": "11111111-1111-4111-8111-111111111111",
                           "ownerId": 7, "messageBatchId": "message-id",
                           "receivedAt": "2026-08-26T15:53:08+08:00", "items": items}
        self.counts = counts
        self.children = []

    def create_child(self, task_name, parameters, _key, **_kwargs):
        child = Child(f"child-{len(self.children)}", task_name)
        self.children.append((child, parameters))
        return child

    def wait_child(self, task_id, _timeout, _poll=1.0):
        return next(child for child, _ in self.children if child.id == task_id)

    def get_task(self, task_id):
        return self.wait_child(task_id, 1)

    def cancel_child(self, task_id):
        return self.wait_child(task_id, 1)

    def get_task_results(self, task_id):
        index = int(task_id.split("-")[-1])
        resources = [{"url": f"https://pbs.twimg.com/media/{index}-{position}.jpg",
                      "fileName": f"image-{index}-{position}.jpg", "mimeType": "image/jpeg"}
                     for position in range(self.counts[index])]
        return {"status": "SUCCEEDED", "steps": [{"stepName": "resolve_x_url",
                "status": "SUCCEEDED", "result": {"resources": resources}}]}


def test_uses_one_message_folder_after_aggregate_threshold():
    """五个链接解析出的十七个媒体必须使用同一消息目录。"""
    items = [{"url": f"https://x.com/user/status/{100 + index}",
              "fileName": f"post-{index}"} for index in range(5)]
    context = Context(items, [1, 1, 1, 11, 3])
    context.parameters["albumTitleText"] = "标题：海边写真\nhttps://x.com/user/status/100"
    result = MODULE.execute(context)
    downloads = [parameters for child, parameters in context.children
                 if child.task_name == "download_http_asset"]
    assert result["mediaCount"] == 17
    assert result["albumFolder"].startswith("海边写真--")
    assert len(downloads) == 17
    assert {item["albumFolder"] for item in downloads} == {result["albumFolder"]}
    assert [item["sourceIndex"] for item in downloads] == list(range(1, 18))


def test_keeps_small_direct_url_batch_in_day_directory():
    """不超过阈值的普通链接不创建消息子目录。"""
    items = [{"url": "https://cdn.example/a.jpg", "fileName": "a.jpg"},
             {"url": "https://cdn.example/b.jpg", "fileName": "b.jpg"}]
    context = Context(items, [])
    result = MODULE.execute(context)
    downloads = [parameters for child, parameters in context.children
                 if child.task_name == "download_http_asset"]
    assert result["mediaCount"] == 2
    assert result["albumFolder"] == ""
    assert {item["albumFolder"] for item in downloads} == {""}


def test_routes_x_user_page_to_profile_task(monkeypatch):
    """消息批次中的 X 用户主页必须进入用户帖子编排任务。"""
    items = [{"url": "https://x.com/example/media", "fileName": "media"},
             {"url": "https://cdn.example/a.jpg", "fileName": "a.jpg"}]
    context = Context(items, [])
    monkeypatch.setattr(MODULE, "wait_all_or_cancel", lambda *_args: None)

    result = MODULE.execute(context)

    profiles = [(child, parameters) for child, parameters in context.children
                if child.task_name == "download_x_user"]
    assert len(profiles) == 1
    assert profiles[0][1]["url"] == "https://x.com/example/media"
    assert len(result["profileTaskIds"]) == 1
