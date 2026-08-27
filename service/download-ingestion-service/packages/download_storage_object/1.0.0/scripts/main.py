#!/usr/bin/env python3
"""校验并复制一个 Storage Gateway 托管对象。"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from datetime import UTC, datetime
from urllib.parse import quote
from urllib.request import Request, urlopen
from uuid import UUID
from zoneinfo import ZoneInfo

from mytools_task_sdk.storage import StorageGatewayClient

DEFAULT_MAX_BYTES = 20 * 1024 * 1024 * 1024
MAX_CONFIGURED_BYTES = 100 * 1024 * 1024 * 1024
SAFE_NAME = re.compile(r"^[^/\\\x00]{1,255}$")
SAFE_DIRECTORY = re.compile(r"[^A-Za-z0-9._-]+")
USERNAME = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


def music_metadata(parameters: dict, file_name: str, opener=urlopen) -> dict:
    """使用模型选择专辑并生成音频描述，模型不可用时确定性降级。"""
    existing = [SAFE_DIRECTORY.sub("_", str(value)).strip("._-")[:96]
                for value in (parameters.get("musicAlbums") or [])][:200]
    existing = [value for value in existing if value]
    fallback = SAFE_DIRECTORY.sub("_", str(parameters.get("musicAlbum") or "Unknown")).strip("._-") or "Unknown"
    description = f"Audio file {file_name}."[:2000]
    base_url = os.getenv("MEDIA_DESCRIPTION_BASE_URL", "").rstrip("/")
    if not base_url:
        return {"album": fallback[:96], "description": description, "generationMode": "METADATA_FALLBACK"}
    prompt = {
        "fileName": file_name,
        "downloadInfo": str(parameters.get("downloadInfo") or "")[:2000],
        "existingAlbums": existing,
    }
    payload = {"model": os.getenv("MEDIA_DESCRIPTION_MODEL", "qwen-vl"), "messages": [{"role": "user",
        "content": "Classify this audio into an existing album when appropriate, otherwise propose a concise album name. "
                   "Also describe the audio in Simplified Chinese. Return JSON with album and description. Input: "
                   + json.dumps(prompt, ensure_ascii=False)}], "response_format": {"type": "json_object"},
        "temperature": 0.2}
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    api_key = os.getenv("MEDIA_DESCRIPTION_API_KEY", "")
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    try:
        request = Request(f"{base_url}/chat/completions", data=json.dumps(payload).encode(), headers=headers)
        with opener(request, timeout=180) as response:
            document = json.loads(response.read().decode("utf-8"))
        content = document["choices"][0]["message"]["content"]
        result = json.loads(content) if isinstance(content, str) else content
        album = SAFE_DIRECTORY.sub("_", str(result.get("album") or "")).strip("._-")[:96]
        generated = " ".join(str(result.get("description") or "").split())[:2000]
        if not album or not generated:
            raise ValueError("music model response is incomplete")
        return {"album": album, "description": generated, "generationMode": "MODEL"}
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        return {"album": fallback[:96], "description": description, "generationMode": "METADATA_FALLBACK"}


def managed_directory(parameters: dict, file_name: str, size: int, music_album: str = "") -> str:
    """按媒体时间、相册和业务类型生成统一存储目录。"""
    username = str(parameters.get("resourceUsername") or "")
    if not USERNAME.fullmatch(username) or username in {".", ".."}:
        raise ValueError("resource username is invalid")
    try:
        received = datetime.fromisoformat(str(parameters.get("receivedAt") or "").replace("Z", "+00:00"))
    except ValueError:
        received = datetime.now(UTC)
    received = received.astimezone(ZoneInfo(os.getenv("DOWNLOAD_STORAGE_TIMEZONE", "Asia/Shanghai")))
    mime = str(parameters.get("assetMimeType") or "application/octet-stream").lower()
    if mime.startswith("video/") and size > 50 * 1024 * 1024:
        stem = SAFE_DIRECTORY.sub("_", Path(file_name).stem).strip("._-") or "video"
        return f"{username}/big_media/{received.strftime('%Y%m%d_%H%M%S')}_{stem[:72]}"
    if mime.startswith(("image/", "video/")):
        directory = f"media/{received.strftime('%Y%m')}/{received.strftime('%Y%m%d')}"
        album = SAFE_DIRECTORY.sub("_", str(parameters.get("albumFolder") or "")).strip("._-")
        path = f"{directory}/{album[:96]}" if album else directory
        return f"{username}/{path}"
    if mime.startswith("audio/") or Path(file_name).suffix.lower() in {".mp3", ".flac", ".aac", ".m4a", ".ogg", ".wav", ".opus"}:
        album = SAFE_DIRECTORY.sub("_", music_album or str(parameters.get("musicAlbum") or "Unknown")).strip("._-") or "Unknown"
        return f"{username}/music/{album[:96]}"
    suffix = Path(file_name).suffix.lower()
    if mime in {"application/epub+zip", "application/pdf"} or suffix in {".epub", ".mobi", ".azw3", ".txt"}:
        return f"{username}/ebook"
    return f"{username}/other"


def execute(parameters: dict, work_dir: Path, client: StorageGatewayClient) -> dict:
    """受限读取、校验并幂等发布一个托管对象。"""
    request_id = str(parameters["downloadRequestId"])
    UUID(request_id)
    item_id = str(parameters["itemId"])
    file_name = str(parameters["fileName"] or "").strip()
    if not item_id or len(item_id) > 255:
        raise ValueError("itemId is invalid")
    if not SAFE_NAME.fullmatch(file_name) or file_name in {".", ".."}:
        raise ValueError("fileName is invalid")
    maximum = int(parameters.get("maxBytes", DEFAULT_MAX_BYTES))
    if maximum <= 0 or maximum > MAX_CONFIGURED_BYTES:
        raise ValueError("maxBytes is outside the supported range")
    source_uri = str(parameters["sourceStorageUri"])
    temporary = work_dir / "source.bin"
    size = client.download(source_uri, temporary, maximum)
    digest = hashlib.sha256()
    with temporary.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    content_sha256 = digest.hexdigest()
    expected = str(parameters.get("expectedSha256") or "").lower()
    if expected and expected != content_sha256:
        raise ValueError("storage object checksum mismatch")
    root_name = str(parameters.get("destinationRootName")
                    or os.getenv("DOWNLOAD_STORAGE_ROOT", "managed"))
    mime = str(parameters.get("assetMimeType") or "").lower()
    is_audio = mime.startswith("audio/") or Path(file_name).suffix.lower() in {
        ".mp3", ".flac", ".aac", ".m4a", ".ogg", ".wav", ".opus"}
    music = music_metadata(parameters, file_name) if is_audio else {}
    relative_path = managed_directory(parameters, file_name, size, str(music.get("album") or "")) + "/" + quote(file_name, safe="")
    storage_uri = client.publish(
        temporary, root_name, relative_path,
        f"download-storage:{request_id}:{item_id}", size, content_sha256)
    return {"requestId": request_id, "itemId": item_id, "fileName": file_name,
            "storageUri": storage_uri, "sizeBytes": size,
            "contentSha256": content_sha256, "music": music}


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
    """执行一个托管对象下载任务。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                  os.getenv("STORAGE_GATEWAY_INTERNAL_TOKEN", ""))
    work_dir = Path(os.environ["TASK_WORK_DIR"])
    write_result(execute(context["parameters"], work_dir, client))


if __name__ == "__main__":
    main()
