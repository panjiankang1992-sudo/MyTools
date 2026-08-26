#!/usr/bin/env python3
"""整条消息 URL 批次解析、目录决策和原子下载编排。"""

from __future__ import annotations

import hashlib
import json
import mimetypes
import os
import re
import tempfile
from pathlib import Path
from urllib.parse import urlparse
from uuid import UUID

from mytools_task_sdk.context import TaskContext
from mytools_task_sdk.orchestration import wait_all_or_cancel

X_PATH = re.compile(r"^/(?:[^/]+/status|i/(?:web/)?status)/\d{1,24}(?:/.*)?$", re.IGNORECASE)
SAFE_PART = re.compile(r"[^A-Za-z0-9_-]+")
MAX_URLS = 20
MAX_MEDIA = 100


def is_x_post(value: str) -> bool:
    """判断地址是否为受支持的 X 帖子地址。"""
    parsed = urlparse(value)
    host = (parsed.hostname or "").lower().removeprefix("www.").removeprefix("mobile.")
    return parsed.scheme in {"http", "https"} and host in {"x.com", "twitter.com"} \
        and X_PATH.fullmatch(parsed.path) is not None


def validate(parameters: dict) -> tuple[str, list[dict]]:
    """验证消息批次参数并返回请求标识和稳定链接清单。"""
    request_id = str(parameters.get("downloadRequestId") or "")
    UUID(request_id)
    raw_items = parameters.get("items")
    if not isinstance(raw_items, list) or not 1 <= len(raw_items) <= MAX_URLS:
        raise ValueError("message URL batch size is invalid")
    items = []
    for index, item in enumerate(raw_items):
        if not isinstance(item, dict):
            raise TypeError("message URL batch item is invalid")
        url = str(item.get("url") or "").strip()
        parsed = urlparse(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or len(url) > 4096:
            raise ValueError("message URL is invalid")
        name = str(item.get("fileName") or f"download-{index}.bin").strip()
        if not name or len(name) > 180 or "/" in name or "\\" in name:
            raise ValueError("message URL file name is invalid")
        items.append({"index": index, "url": url, "fileName": name})
    return request_id, items


def successful_result(payload: dict, step_name: str) -> dict:
    """读取一个成功子任务的指定步骤结果。"""
    if not isinstance(payload, dict) or payload.get("status") != "SUCCEEDED":
        raise RuntimeError("message URL resolver child did not succeed")
    steps = payload.get("steps")
    if not isinstance(steps, list):
        raise TypeError("message URL resolver returned invalid results")
    selected = [step.get("result") for step in steps if isinstance(step, dict)
                and step.get("stepName") == step_name and step.get("status") == "SUCCEEDED"
                and isinstance(step.get("result"), dict)]
    if len(selected) != 1:
        raise RuntimeError("message URL resolver returned no unique result")
    return selected[0]


def direct_resource(item: dict) -> dict:
    """把普通 HTTP 链接转换为单个原子下载资源。"""
    name = item["fileName"]
    return {"sourceAction": item["index"], "resourceIndex": 1, "url": item["url"],
            "fileName": name, "mimeType": mimetypes.guess_type(name)[0]
            or "application/octet-stream"}


def resolved_resources(item: dict, result: dict) -> list[dict]:
    """验证 X 解析结果并补充消息动作身份。"""
    values = result.get("resources")
    if not isinstance(values, list) or not values:
        raise RuntimeError("X resolver returned no media")
    resources = []
    for position, value in enumerate(values, start=1):
        if not isinstance(value, dict):
            raise TypeError("X resolver returned invalid media")
        url = str(value.get("url") or "")
        name = str(value.get("fileName") or "")
        if not url.startswith("https://") or not name:
            raise RuntimeError("X resolver returned unsafe media")
        resources.append({"sourceAction": item["index"], "resourceIndex": position,
                          "url": url, "fileName": name,
                          "mimeType": str(value.get("mimeType") or "application/octet-stream")})
    return resources


def execute(context: TaskContext) -> dict:
    """先解析整批链接，再用统一目录创建每个媒体下载子任务。"""
    parameters = context.parameters
    request_id, items = validate(parameters)
    owner_id = int(parameters.get("ownerId") or 0)
    maximum_bytes = int(parameters.get("maxBytesPerItem", 2 * 1024 * 1024 * 1024))
    threshold = int(parameters.get("albumMediaThreshold", 10))
    if maximum_bytes < 1 or maximum_bytes > 20 * 1024 * 1024 * 1024:
        raise ValueError("message URL item byte limit is invalid")
    if threshold < 1 or threshold > MAX_MEDIA:
        raise ValueError("message URL album threshold is invalid")

    resolver_children = []
    resolver_items = []
    resources = []
    for item in items:
        if not is_x_post(item["url"]):
            resources.append(direct_resource(item))
            continue
        fingerprint = hashlib.sha256(item["url"].encode()).hexdigest()
        child = context.create_child(
            "download_resolve_x_url",
            {"url": item["url"], "maxMedia": 40, "resolveOnly": True},
            f"message-url-resolve:{request_id}:{item['index']}:{fingerprint}",
            business_type="DOWNLOAD_REQUEST", business_id=request_id)
        resolver_children.append(child)
        resolver_items.append(item)
    wait_all_or_cancel(context, resolver_children, 300)
    for item, child in zip(resolver_items, resolver_children, strict=True):
        result = successful_result(context.get_task_results(child.id), "resolve_x_url")
        resources.extend(resolved_resources(item, result))
    if not 1 <= len(resources) <= MAX_MEDIA:
        raise ValueError("message resolved media count is invalid")
    resources.sort(key=lambda value: (value["sourceAction"], value["resourceIndex"]))

    album_folder = ""
    if len(resources) > threshold:
        raw_batch = str(parameters.get("messageBatchId") or request_id)
        batch = SAFE_PART.sub("_", raw_batch).strip("_")
        album_folder = f"message-{batch[:48]}"
    download_children = []
    for source_index, resource in enumerate(resources, start=1):
        item_id = f"message:{resource['sourceAction']}:{resource['resourceIndex']}"
        fingerprint = hashlib.sha256(resource["url"].encode()).hexdigest()
        child = context.create_child(
            "download_http_asset",
            {"downloadRequestId": request_id, "itemId": item_id, "url": resource["url"],
             "fileName": resource["fileName"], "sourceIndex": source_index,
             "maxBytes": maximum_bytes, "ownerId": owner_id,
             "receivedAt": str(parameters.get("receivedAt") or ""),
             "albumFolder": album_folder, "assetMimeType": resource["mimeType"],
             "assetSourceBusinessId": f"{request_id}:{item_id}"},
            f"message-media:{request_id}:{source_index}:{fingerprint}",
            business_type="DOWNLOAD_REQUEST", business_id=request_id)
        download_children.append(child)
    wait_all_or_cancel(context, download_children, 1800)
    return {"requestId": request_id, "inputCount": len(items), "mediaCount": len(resources),
            "albumFolder": album_folder,
            "resolverTaskIds": [child.id for child in resolver_children],
            "downloadTaskIds": [child.id for child in download_children]}


def write_result(result: dict) -> None:
    """原子写入消息批次结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行消息 URL 批次。"""
    context = TaskContext.load()
    write_result(execute(context))


if __name__ == "__main__":
    main()
