#!/usr/bin/env python3
"""从 Storage Gateway 远端 Provider 读取并发布一个受管对象。"""
from __future__ import annotations
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import tempfile
from uuid import UUID
from mytools_task_sdk.storage import StorageGatewayClient

DEFAULT_MAX_BYTES = 20 * 1024 * 1024 * 1024
MAX_CONFIGURED_BYTES = 100 * 1024 * 1024 * 1024
SAFE_NAME = re.compile(r"^[^/\\\x00]{1,255}$")
MAX_COMPONENT_BYTES = 240

def content_mime(source: Path, filename: str) -> str:
    """优先根据文件头识别媒体类型，避免通用 MIME 与已有去重资产冲突。"""
    with source.open("rb") as stream:
        prefix = stream.read(32)
    if prefix.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if prefix.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if prefix[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if prefix[:4] == b"RIFF" and prefix[8:12] == b"WEBP":
        return "image/webp"
    if prefix[4:8] == b"ftyp" and prefix[8:12] in (b"isom", b"iso2", b"mp41", b"mp42", b"avc1", b"M4V "):
        return "video/mp4"
    return mimetypes.guess_type(filename)[0] or "application/octet-stream"

def local_component(value: str) -> str:
    """按 UTF-8 字节限制单段名称，保留中文及扩展名并用摘要避免截断碰撞。"""
    if not value or value in {".", ".."} or any(ord(c) < 32 or ord(c) == 127 or c in "/\\" for c in value):
        raise ValueError("destinationRelativePath is invalid")
    original = value.encode("utf-8")
    # 存储 URI 直接引用本地路径，仅替换 URI 不安全字符，中文不做膨胀编码。
    normalized = "".join("_" if c.isspace() or c in '%?#<>"^`{|}[]' else c for c in value)
    encoded = normalized.encode("utf-8")
    if normalized == value and len(encoded) <= MAX_COMPONENT_BYTES:
        return value
    extension = Path(normalized).suffix
    if len(extension.encode("utf-8")) > 32:
        extension = ""
    suffix = "~" + hashlib.sha256(original).hexdigest()[:20] + extension
    stem = normalized[:-len(extension)] if extension else normalized
    prefix = stem.encode("utf-8")[:MAX_COMPONENT_BYTES - len(suffix.encode("utf-8"))].decode("utf-8", errors="ignore")
    return prefix + suffix

def local_path(request_id: str, value: str) -> str:
    """文件系统路径不做 URL 编码，提前拒绝危险片段和超长总路径。"""
    result = request_id + "/" + "/".join(local_component(part) for part in value.split("/"))
    if len(result) > 2048 or len(result.encode("utf-8")) > 3500:
        raise ValueError("destinationRelativePath exceeds maximum length")
    return result

def execute(parameters: dict, work_dir: Path, client: StorageGatewayClient) -> dict:
    """受限读取远端对象、校验摘要并幂等发布。"""
    request_id = str(UUID(str(parameters["downloadRequestId"])))
    provider_id = str(UUID(str(parameters["sourceProviderId"])))
    item_id = str(parameters["itemId"])
    file_name = str(parameters.get("fileName") or "").strip()
    source_path = str(parameters.get("sourcePath") or "").strip()
    if not item_id or len(item_id) > 255:
        raise ValueError("itemId is invalid")
    if not SAFE_NAME.fullmatch(file_name) or file_name in {".", ".."}:
        raise ValueError("fileName is invalid")
    if not source_path or source_path.startswith("/") or "\\" in source_path or ".." in source_path.split("/"):
        raise ValueError("sourcePath is invalid")
    maximum = int(parameters.get("maxBytes", DEFAULT_MAX_BYTES))
    if maximum <= 0 or maximum > MAX_CONFIGURED_BYTES:
        raise ValueError("maxBytes is outside the supported range")
    root_name = str(parameters.get("destinationRootName") or "managed")
    destination_path = str(parameters.get("destinationRelativePath") or file_name).strip()
    relative_path = local_path(request_id, destination_path)
    temporary = work_dir / "remote-source.bin"
    size = client.download_remote(provider_id, source_path, temporary, maximum)
    digest = hashlib.sha256()
    with temporary.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    content_sha256 = digest.hexdigest()
    expected_size = parameters.get("expectedSize")
    if expected_size is not None and int(expected_size) != size:
        raise ValueError("remote storage object size mismatch")
    storage_uri = client.publish(temporary, root_name, relative_path,
        f"download-remote-v3:{request_id}:{item_id}", size, content_sha256)
    return {"requestId": request_id, "itemId": item_id, "fileName": file_name,
            "storageUri": storage_uri, "sizeBytes": size, "contentSha256": content_sha256,
            "mimeType": content_mime(temporary, file_name)}

def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":")); temporary = Path(handle.name)
    temporary.replace(target)

def main() -> None:
    """执行一个远端存储对象下载任务。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                  os.environ["STORAGE_GATEWAY_INTERNAL_TOKEN"])
    write_result(execute(context["parameters"], Path(os.environ["TASK_WORK_DIR"]), client))

if __name__ == "__main__":
    main()
