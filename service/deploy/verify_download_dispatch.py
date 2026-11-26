"""下载任务派发容量门禁；服务健康不代表启用任务具备可运行容量。"""
import json
import os
from urllib.request import Request, urlopen

DOWNLOAD_TASKS = frozenset({"download_http_asset", "download_message_attachment",
                           "download_remote_storage_object", "download_storage_object"})


def blocked_downloads(definitions):
    """列出已启用但并发容量为零或无效的下载任务。"""
    blocked = []
    for definition in definitions:
        if definition.get("name") not in DOWNLOAD_TASKS or definition.get("enabled") is not True:
            continue
        capacity = definition.get("maxConcurrency")
        if not isinstance(capacity, int) or isinstance(capacity, bool) or capacity < 1:
            blocked.append(definition["name"])
    return sorted(blocked)


def validate_definitions(definitions):
    """拒绝缺失任务的响应，防止空列表被误判为恢复。"""
    if not isinstance(definitions, list) or any(not isinstance(item, dict) for item in definitions):
        raise ValueError("task definition response is invalid")
    if not DOWNLOAD_TASKS.issubset({item.get("name") for item in definitions}):
        raise ValueError("required download task definitions are missing")


def main():
    """用内部只读接口核验派发容量，不输出令牌或修改调度数据库。"""
    token = os.environ["TASK_OPERATOR_INTERNAL_TOKEN"]
    request = Request(os.getenv("TASK_SCHEDULER_URL", "http://127.0.0.1:23410").rstrip("/")
        + "/api/v1/task-definitions", headers={"X-Task-Service-Id": "task-operator-service",
        "X-Task-Internal-Token": token})
    with urlopen(request, timeout=10) as response:
        definitions = json.load(response)
    validate_definitions(definitions)
    blocked = blocked_downloads(definitions)
    print(json.dumps({"downloadDispatchReady": not blocked, "blockedTasks": blocked}))
    return 1 if blocked else 0


if __name__ == "__main__":
    raise SystemExit(main())
