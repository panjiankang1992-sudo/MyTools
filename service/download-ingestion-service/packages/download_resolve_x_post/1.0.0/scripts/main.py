#!/usr/bin/env python3
"""解析一个 X 帖子并为每个媒体创建原子下载子任务。"""

from __future__ import annotations

import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse
from urllib.request import ProxyHandler, Request, build_opener
from uuid import UUID

from mytools_task_sdk.context import TaskContext
from mytools_task_sdk.orchestration import wait_all_or_cancel

X_PATH = re.compile(r"^/(?:[^/]+/status|i/(?:web/)?status)/(\d{1,24})(?:/.*)?$", re.I)
SAFE_PART = re.compile(r"[^A-Za-z0-9_-]+")
MAX_METADATA_BYTES = 8 * 1024 * 1024
DEFAULT_FALLBACK_API = "https://api.fxtwitter.com/status/{tweet_id}"


def canonical_x_url(value: object) -> tuple[str, str]:
    """只接受单个公开 X 帖子 URL 并生成稳定地址。"""
    parsed = urlparse(str(value or "").strip())
    host = (parsed.hostname or "").lower().removeprefix("www.").removeprefix("mobile.")
    match = X_PATH.match(parsed.path)
    if parsed.scheme not in {"http", "https"} or host not in {"x.com", "twitter.com"} or not match:
        raise ValueError("url must identify one X status")
    tweet_id = match.group(1)
    return f"https://x.com/i/web/status/{tweet_id}", tweet_id


def parse_resources(raw: bytes, tweet_id: str, maximum: int) -> list[dict]:
    """从 gallery-dl 有界元数据中提取唯一的 twimg 媒体。"""
    if len(raw) > MAX_METADATA_BYTES:
        raise ValueError("X metadata exceeds limit")
    try:
        messages = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ValueError("gallery-dl returned invalid metadata") from exception
    if not isinstance(messages, list):
        raise ValueError("gallery-dl metadata must be a list")
    resources = []
    seen = set()
    for message in messages:
        if not isinstance(message, list) or len(message) < 3 or message[0] != 3:
            continue
        url, metadata = message[1], message[2]
        if not isinstance(url, str) or not isinstance(metadata, dict):
            continue
        parsed = urlparse(url)
        if parsed.scheme != "https" or not (parsed.hostname or "").lower().endswith(".twimg.com"):
            continue
        resource_tweet = str(metadata.get("tweet_id") or tweet_id)
        if not re.fullmatch(r"[0-9]{1,24}", resource_tweet):
            continue
        try:
            index = int(metadata.get("num") or metadata.get("count") or len(resources) + 1)
        except (TypeError, ValueError):
            index = len(resources) + 1
        extension = str(metadata.get("extension") or "").strip(". ").lower()
        if not extension or not re.fullmatch(r"[a-z0-9]{1,10}", extension):
            extension = Path(parsed.path).suffix.lstrip(".").lower() or "bin"
        base = SAFE_PART.sub("_", str(metadata.get("filename") or "media")).strip("_") or "media"
        file_name = f"x-{resource_tweet}-{index:02d}-{base[:120]}.{extension}"
        key = resource_tweet, index, url
        if key in seen:
            continue
        seen.add(key)
        resources.append({"tweetId": resource_tweet, "index": index, "url": url,
                          "fileName": file_name,
                          "mimeType": mimetypes.guess_type(file_name)[0]
                          or "application/octet-stream"})
        if len(resources) > maximum:
            raise ValueError("X media count exceeds limit")
    if not resources:
        raise ValueError("X post contains no downloadable media")
    return resources


def original_photo_url(value: str) -> str:
    """将 FxTwitter 图片地址规范化为原图地址。"""
    parsed = urlparse(value)
    query = dict(parse_qsl(parsed.query, keep_blank_values=True))
    query["name"] = "orig"
    return urlunparse(parsed._replace(query=urlencode(query)))


def parse_fallback_resources(raw: bytes, tweet_id: str, maximum: int) -> list[dict]:
    """从有界 FxTwitter 响应递归提取主帖和引用帖媒体。"""
    if len(raw) > MAX_METADATA_BYTES:
        raise ValueError("X fallback metadata exceeds limit")
    try:
        payload = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ValueError("X fallback returned invalid metadata") from exception
    if not isinstance(payload, dict) or payload.get("code") not in (None, 200):
        raise ValueError("X fallback returned an unsuccessful response")
    root = payload.get("tweet")
    if not isinstance(root, dict) or str(root.get("id") or tweet_id) != tweet_id:
        raise ValueError("X fallback returned mismatched tweet metadata")
    resources: list[dict] = []
    seen_posts: set[str] = set()
    seen_urls: set[tuple[str, str]] = set()

    def collect(post: dict) -> None:
        resource_tweet = str(post.get("id") or tweet_id)
        if not re.fullmatch(r"[0-9]{1,24}", resource_tweet) or resource_tweet in seen_posts:
            return
        seen_posts.add(resource_tweet)
        media = post.get("media")
        items = []
        if isinstance(media, dict):
            if isinstance(media.get("all"), list):
                items = media["all"]
            else:
                for key in ("photos", "videos"):
                    if isinstance(media.get(key), list):
                        items.extend(media[key])
        index = 0
        for item in items:
            if not isinstance(item, dict) or not isinstance(item.get("url"), str):
                continue
            media_type = str(item.get("type") or "file").lower()
            url = original_photo_url(item["url"]) if media_type == "photo" else item["url"]
            parsed = urlparse(url)
            if parsed.scheme != "https" or not (parsed.hostname or "").lower().endswith(".twimg.com"):
                continue
            key = resource_tweet, url
            if key in seen_urls:
                continue
            seen_urls.add(key)
            index += 1
            query = dict(parse_qsl(parsed.query, keep_blank_values=True))
            extension = str(item.get("format") or query.get("format") or
                            Path(parsed.path).suffix.lstrip(".") or
                            ("mp4" if media_type in {"video", "gif", "animated_gif"} else "bin"))
            extension = extension.lower().strip(". ")
            if not re.fullmatch(r"[a-z0-9]{1,10}", extension):
                extension = "bin"
            media_id = SAFE_PART.sub("_", str(item.get("id") or "media")).strip("_") or "media"
            file_name = f"x-{resource_tweet}-{index:02d}-{media_id[:120]}.{extension}"
            resources.append({"tweetId": resource_tweet, "index": index, "url": url,
                              "fileName": file_name,
                              "mimeType": str(item.get("format") or "")
                              or mimetypes.guess_type(file_name)[0]
                              or "application/octet-stream"})
            if len(resources) > maximum:
                raise ValueError("X media count exceeds limit")
        quote = post.get("quote")
        if isinstance(quote, dict):
            collect(quote)

    collect(root)
    if not resources:
        raise ValueError("X post contains no downloadable media")
    return resources


def fallback_resources(tweet_id: str, maximum: int, opener=None) -> list[dict]:
    """通过受控 FxTwitter API 回退解析公开帖子。"""
    endpoint = os.getenv("X_FALLBACK_API_URL", DEFAULT_FALLBACK_API).format(tweet_id=tweet_id)
    parsed = urlparse(endpoint)
    if parsed.scheme != "https" or parsed.hostname not in {"api.fxtwitter.com", "api.vxtwitter.com"}:
        raise ValueError("X fallback API URL is invalid")
    proxy = os.getenv("X_PROXY_URL", "").strip()
    handlers = [ProxyHandler({"http": proxy, "https": proxy})] if proxy else []
    request_opener = opener or build_opener(*handlers).open
    request = Request(endpoint, headers={"Accept": "application/json",
                                         "User-Agent": "MyTools-X-Resolver/1.0"})
    with request_opener(request, timeout=90) as response:
        raw = response.read(MAX_METADATA_BYTES + 1)
    return parse_fallback_resources(raw, tweet_id, maximum)


def resolve(parameters: dict, runner=subprocess.run) -> tuple[str, list[dict]]:
    """运行无下载模式的 gallery-dl 并返回标准化媒体。"""
    canonical, tweet_id = canonical_x_url(parameters["url"])
    maximum = int(parameters.get("maxMedia", 20))
    if maximum < 1 or maximum > 40:
        raise ValueError("maxMedia is outside the supported range")
    command = [os.getenv("GALLERY_DL_BINARY", "gallery-dl"), "-J", "--no-download",
               "--no-input", "--no-colors", "-o", "extractor.twitter.videos=true",
               "-o", "extractor.twitter.quoted=true", "-o", "extractor.twitter.cards=false"]
    cookie_path = os.getenv("X_COOKIES_PATH", "")
    if cookie_path:
        if not Path(cookie_path).is_file():
            raise ValueError("configured X cookie file is missing")
        command.extend(("--cookies", cookie_path))
    proxy = os.getenv("X_PROXY_URL", "")
    if proxy:
        command.extend(("--proxy", proxy))
    command.append(canonical)
    try:
        completed = runner(command, capture_output=True, timeout=90, check=False)
        if completed.returncode != 0:
            raise ValueError("gallery-dl could not resolve X media")
        resources = parse_resources(completed.stdout, tweet_id, maximum)
    except (OSError, RuntimeError, ValueError):
        resources = fallback_resources(tweet_id, maximum)
    return tweet_id, resources


def execute(context: TaskContext, parameters: dict, resources: list[dict], tweet_id: str) -> dict:
    """并行创建媒体子任务，并在失败时取消尚未完成的同批任务。"""
    request_id = str(parameters["downloadRequestId"])
    UUID(request_id)
    maximum_bytes = int(parameters.get("maxBytesPerItem", 2 * 1024 * 1024 * 1024))
    album_threshold = int(parameters.get("albumMediaThreshold", 10))
    album_folder = ""
    if len(resources) > album_threshold:
        batch_id = SAFE_PART.sub("_", str(parameters.get("messageBatchId") or request_id)).strip("_")
        album_folder = f"message-{batch_id[:48]}"
    children = []
    for source_index, resource in enumerate(resources, start=1):
        item_id = f"x:{resource['tweetId']}:{resource['index']}"
        fingerprint = hashlib.sha256(resource["url"].encode()).hexdigest()
        child = context.create_child(
            "download_http_asset",
            {"downloadRequestId": request_id, "itemId": item_id, "url": resource["url"],
             "fileName": resource["fileName"], "sourceIndex": source_index,
             "maxBytes": maximum_bytes,
             "ownerId": int(parameters.get("ownerId") or 0),
             "resourceUsername": str(parameters.get("resourceUsername") or ""),
             "receivedAt": str(parameters.get("receivedAt") or ""),
             "albumFolder": album_folder,
             "assetMimeType": resource["mimeType"],
             "assetSourceBusinessId": f"{request_id}:{item_id}"},
            f"x-media:{request_id}:{resource['tweetId']}:{resource['index']}:{fingerprint}",
            business_type="DOWNLOAD_REQUEST", business_id=request_id)
        children.append(child)
    wait_all_or_cancel(context, children, 1500)
    return {"requestId": request_id, "tweetId": tweet_id,
            "mediaCount": len(children), "childTaskIds": [child.id for child in children]}


def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行一个 X 帖子解析父任务。"""
    context = TaskContext.load()
    parameters = context.parameters
    tweet_id, resources = resolve(parameters)
    # 消息批次父任务先只收集所有链接的媒体清单，再统一决定目录并创建下载子任务。
    if parameters.get("resolveOnly") is True:
        write_result({"tweetId": tweet_id, "resources": resources,
                      "mediaCount": len(resources)})
        return
    write_result(execute(context, parameters, resources, tweet_id))


if __name__ == "__main__":
    main()
