#!/usr/bin/env python3
"""展开一个托管导入对象，并按时间及来源目录编排逐文件入库任务。"""

from __future__ import annotations

from collections import Counter
from datetime import UTC, datetime
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from uuid import UUID

from mytools_task_sdk.context import TaskContext
from mytools_task_sdk.orchestration import wait_all_or_cancel
from mytools_task_sdk.storage import StorageGatewayClient

ARCHIVE_MAGIC = ((b"PK\x03\x04", "zip"), (b"Rar!\x1a\x07", "rar"),
                 (b"7z\xbc\xaf\x27\x1c", "7z"))
IMAGE_SUFFIXES = {".avif", ".gif", ".heic", ".heif", ".jpeg", ".jpg", ".png", ".webp"}
GENERIC_ALBUMS = {"image", "images", "media", "photo", "photos", "temp", "tmp", "video", "videos",
                  "图片", "视频", "电报"}
SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]+")
MAXIMUM_BYTES = 100 * 1024 * 1024 * 1024


def archive_kind(path: Path) -> str:
    """按文件内容识别受支持的压缩包。"""
    with path.open("rb") as stream:
        head = stream.read(8)
    return next((kind for magic, kind in ARCHIVE_MAGIC if head.startswith(magic)), "")


def sha256(path: Path) -> str:
    """流式计算文件摘要。"""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def safe_files(root: Path, maximum: int) -> list[Path]:
    """列出解压后的普通文件并拒绝链接及超限内容。"""
    files = []
    total = 0
    resolved = root.resolve()
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError("local import archive contains a symbolic link")
        if path.is_file():
            path.resolve().relative_to(resolved)
            total += path.stat().st_size
            if total > maximum:
                raise ValueError("local import archive exceeds maxBytes")
            files.append(path)
    return sorted(files, key=lambda value: value.relative_to(root).as_posix())


def extract_archive(source: Path, destination: Path, binary: str) -> None:
    """使用 7-Zip 在独立工作目录解压。"""
    command = [binary, "x", "-y", "-bd", "-bso0", "-bsp0", "-p__MYTOOLS_NO_PASSWORD__",
               f"-o{destination}", str(source)]
    try:
        result = subprocess.run(command, capture_output=True, timeout=21600, check=False)
    except FileNotFoundError as exception:
        raise RuntimeError("7z is not installed") from exception
    if result.returncode != 0:
        raise ValueError("local import archive is encrypted or invalid")


def expand_nested_archives(root: Path, maximum: int, max_depth: int, binary: str) -> list[Path]:
    """递归展开有界压缩包，每层均重新执行路径与总字节校验。"""
    queue = [(path, 1) for path in safe_files(root, maximum)]
    while queue:
        path, depth = queue.pop(0)
        if not archive_kind(path) or depth >= max_depth:
            continue
        digest = sha256(path)[:8]
        name = SAFE_NAME.sub("_", path.stem).strip("._-") or "archive"
        destination = path.parent / f"{name}__import_d{depth + 1}_{digest}"
        destination.mkdir()
        extract_archive(path, destination, binary)
        path.unlink()
        queue.extend((child, depth + 1) for child in safe_files(destination, maximum))
        safe_files(root, maximum)
    return safe_files(root, maximum)


def mime_type(path: Path) -> str:
    """根据文件名推断稳定的 MIME 类型。"""
    return mimetypes.guess_type(path.name)[0] or "application/octet-stream"


def created_at(path: Path, mime: str, binary: str) -> datetime:
    """图片优先使用 EXIF 拍摄时间，失败时回退文件时间。"""
    fallback = datetime.fromtimestamp(path.stat().st_mtime, UTC)
    if not mime.startswith("image/") or shutil.which(binary) is None:
        return fallback
    result = subprocess.run([binary, "-j", "-d", "%Y-%m-%dT%H:%M:%S%z", "-DateTimeOriginal",
                             "-CreateDate", "-FileModifyDate", str(path)], capture_output=True,
                            timeout=30, check=False)
    if result.returncode != 0:
        return fallback
    try:
        row = json.loads(result.stdout)[0]
    except (json.JSONDecodeError, IndexError, TypeError):
        return fallback
    for key in ("DateTimeOriginal", "CreateDate", "FileModifyDate"):
        try:
            value = datetime.fromisoformat(str(row.get(key) or ""))
            return value.replace(tzinfo=value.tzinfo or UTC)
        except ValueError:
            continue
    return fallback


def album_folder(path: Path, root: Path, parent_counts: Counter[Path], maximum: int) -> str:
    """为有意义且数量受限的图片目录生成稳定相册名。"""
    relative = path.relative_to(root)
    count = parent_counts[relative.parent]
    if not 2 <= count <= maximum or relative.parent == Path("."):
        return ""
    title = SAFE_NAME.sub("_", relative.parent.name).strip("._-")
    if not title or title.casefold() in GENERIC_ALBUMS:
        return ""
    digest = hashlib.sha256(relative.parent.as_posix().encode()).hexdigest()[:8]
    return f"{title[:80]}--{digest}"


def execute(context: TaskContext, storage: StorageGatewayClient) -> dict:
    """下载、可选解压并编排单文件子任务。"""
    parameters = context.parameters
    request_id = str(UUID(str(parameters["downloadRequestId"])))
    maximum = int(parameters.get("maxBytes", 20 * 1024 * 1024 * 1024))
    max_depth = int(parameters.get("maxArchiveDepth", 4))
    if not 1 <= maximum <= MAXIMUM_BYTES:
        raise ValueError("maxBytes is outside the supported range")
    if not 1 <= max_depth <= 4:
        raise ValueError("maxArchiveDepth is outside the supported range")
    source = Path(os.environ["TASK_WORK_DIR"]) / "source.bin"
    size = storage.download(str(parameters["sourceStorageUri"]), source, maximum)
    expected = str(parameters.get("expectedSha256") or "").lower()
    if expected and sha256(source) != expected:
        raise ValueError("local import checksum mismatch")
    root = source.parent / "content"
    root.mkdir()
    archive = bool(archive_kind(source))
    if archive:
        binary = os.getenv("SEVEN_ZIP_BINARY", "7z")
        extract_archive(source, root, binary)
        files = expand_nested_archives(root, maximum, max_depth, binary)
    else:
        target = root / str(parameters["fileName"])
        if target.name in {"", ".", ".."} or target.name != str(parameters["fileName"]):
            raise ValueError("fileName is invalid")
        shutil.copy2(source, target)
        files = [target]
    if not files:
        raise ValueError("local import produced no files")
    image_counts = Counter(path.relative_to(root).parent for path in files
                           if path.suffix.lower() in IMAGE_SUFFIXES)
    children = []
    total = 0
    owner_id = int(parameters.get("ownerId") or 0)
    album_maximum = int(parameters.get("albumMaxItems", 100))
    for index, path in enumerate(files, start=1):
        item_size = path.stat().st_size
        total += item_size
        digest = sha256(path)
        relative = path.relative_to(root).as_posix()
        staging_uri = storage.publish(path, "managed", f"staging/local-import/{request_id}/{relative}",
                                      f"local-import:{request_id}:{index}", item_size, digest)
        mime = mime_type(path)
        album = album_folder(path, root, image_counts, album_maximum) if mime.startswith("image/") else ""
        item_id = f"local-import:{index}"
        child = context.create_child("download_storage_object", {
            "downloadRequestId": request_id, "itemId": item_id, "sourceStorageUri": staging_uri,
            "fileName": path.name, "expectedSha256": digest, "maxBytes": maximum,
            "sourceIndex": index - 1,
            "destinationRootName": str(parameters.get("destinationRootName")
                                       or os.getenv("DOWNLOAD_STORAGE_ROOT", "managed")),
            "ownerId": owner_id, "receivedAt": created_at(path, mime, os.getenv("EXIFTOOL_BINARY", "exiftool")).isoformat(),
            "assetMimeType": mime, "albumFolder": album,
            "assetSourceBusinessId": f"{request_id}:{item_id}"},
            f"local-import-object:{request_id}:{index}:{digest}",
            business_type="DOWNLOAD_REQUEST", business_id=request_id)
        children.append(child)
    wait_all_or_cancel(context, children, 21600)
    return {"requestId": request_id, "itemCount": len(files), "totalBytes": total,
            "archiveExtracted": archive, "childTaskIds": [child.id for child in children]}


def main() -> None:
    """执行本地导入父任务。"""
    context = TaskContext.load()
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.getenv("STORAGE_GATEWAY_INTERNAL_TOKEN", ""))
    result = execute(context, storage)
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


if __name__ == "__main__":
    main()
