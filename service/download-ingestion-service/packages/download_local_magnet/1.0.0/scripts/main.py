#!/usr/bin/env python3
"""使用 aria2 持久断点下载一个 magnet，并编排统一资产入库子任务。"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import parse_qs, urlparse
from uuid import UUID

from mytools_task_sdk.context import TaskContext
from mytools_task_sdk.orchestration import wait_all_or_cancel
from mytools_task_sdk.storage import StorageGatewayClient

BTIH = re.compile(r"^(?:[0-9a-fA-F]{40}|[A-Z2-7]{32})$")
IGNORED_NAMES = {"aria2.session"}
MAXIMUM_BYTES = 100 * 1024 * 1024 * 1024


def validate_magnet(value: object) -> str:
    """只接受带一个合法 BTIH 的 magnet URI。"""
    uri = str(value or "").strip()
    parsed = urlparse(uri)
    hashes = [item.rsplit(":", 1)[-1] for item in parse_qs(parsed.query).get("xt", [])
              if item.lower().startswith("urn:btih:")]
    if parsed.scheme.lower() != "magnet" or not hashes or not BTIH.fullmatch(hashes[0]):
        raise ValueError("magnetUri must contain one valid BTIH")
    if len(uri.encode("utf-8")) > 8192:
        raise ValueError("magnetUri exceeds maximum length")
    return uri


def downloaded_files(root: Path) -> list[Path]:
    """列出 aria2 已完成的普通文件并拒绝路径逃逸。"""
    resolved = root.resolve()
    files = []
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError("magnet output contains a symbolic link")
        if not path.is_file() or path.name in IGNORED_NAMES or path.suffix.lower() in {".aria2", ".torrent"}:
            continue
        path.resolve().relative_to(resolved)
        files.append(path)
    return sorted(files, key=lambda item: item.relative_to(root).as_posix())


def directory_size(root: Path) -> int:
    """计算当前下载字节数，用于执行期间硬限制。"""
    return sum(path.stat().st_size for path in root.rglob("*") if path.is_file()
               and path.name not in IGNORED_NAMES and path.suffix.lower() != ".aria2")


def sha256(path: Path) -> str:
    """以流式方式计算文件摘要。"""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def run_aria2(uri: str, root: Path, maximum: int, binary: str = "aria2c",
              poll_seconds: float = 1.0) -> None:
    """执行受限 aria2 命令并保留控制文件以支持任务重试续传。"""
    command = [binary, "--continue=true", "--allow-overwrite=true",
               "--auto-file-renaming=false", "--file-allocation=none",
               "--auto-save-interval=5", "--check-integrity=true", "--seed-time=0",
               "--max-upload-limit=1K", "--bt-save-metadata=true",
               "--save-session-interval=5", f"--save-session={root / 'aria2.session'}",
               "--summary-interval=0", "--console-log-level=warn", "--download-result=hide",
               "--disable-ipv6=true", f"--dir={root}", uri]
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except FileNotFoundError as exception:
        raise RuntimeError("aria2c is not installed") from exception
    output = bytearray()
    try:
        while process.poll() is None:
            if directory_size(root) > maximum:
                process.terminate()
                process.wait(timeout=10)
                raise ValueError("magnet download exceeds maxTotalBytes")
            time.sleep(poll_seconds)
        if process.stdout is not None:
            output.extend(process.stdout.read(65536))
        if process.returncode != 0:
            detail = output.decode("utf-8", "replace").strip()[-1200:]
            raise RuntimeError(f"aria2 exited with {process.returncode}: {detail}")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()


def execute(context: TaskContext, storage: StorageGatewayClient,
            runner=run_aria2) -> dict:
    """下载、发布暂存对象，并为每个文件创建统一入库子任务。"""
    parameters = context.parameters
    request_id = str(UUID(str(parameters["downloadRequestId"])))
    uri = validate_magnet(parameters["magnetUri"])
    maximum = int(parameters.get("maxTotalBytes", 20 * 1024 * 1024 * 1024))
    if maximum < 1 or maximum > MAXIMUM_BYTES:
        raise ValueError("maxTotalBytes is outside the supported range")
    staging_root = Path(os.environ["LOCAL_MAGNET_STAGING_ROOT"]).resolve()
    staging_root.mkdir(parents=True, exist_ok=True)
    work = staging_root / request_id
    work.mkdir(parents=True, exist_ok=True)
    runner(uri, work, maximum, os.getenv("ARIA2_BINARY", "aria2c"))
    files = downloaded_files(work)
    total = sum(path.stat().st_size for path in files)
    if not files or total > maximum:
        raise ValueError("magnet download produced no valid files or exceeded maxTotalBytes")
    children = []
    owner_id = int(parameters.get("ownerId") or 0)
    for index, path in enumerate(files, start=1):
        relative = path.relative_to(work).as_posix()
        size = path.stat().st_size
        digest = sha256(path)
        source_uri = storage.publish(path, "managed", f"staging/magnet/{request_id}/{relative}",
                                     f"local-magnet:{request_id}:{index}", size, digest)
        item_id = f"local-magnet:{index}"
        file_name = f"{index:04d}--{path.name}" if len(files) > 1 else path.name
        child = context.create_child("download_storage_object", {
            "downloadRequestId": request_id, "itemId": item_id,
            "sourceStorageUri": source_uri, "fileName": file_name,
            "expectedSha256": digest, "maxBytes": maximum,
            "destinationRootName": str(parameters.get("destinationRootName") or "downloads"),
            "ownerId": owner_id, "assetSourceBusinessId": f"{request_id}:{item_id}"},
            f"local-magnet-object:{request_id}:{index}:{digest}",
            business_type="DOWNLOAD_REQUEST", business_id=request_id)
        children.append(child)
    wait_all_or_cancel(context, children, 21600)
    return {"requestId": request_id, "status": "READY", "itemCount": len(files),
            "totalBytes": total, "childTaskIds": [child.id for child in children]}


def write_result(result: dict) -> None:
    """原子写入父任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行本地 magnet 父任务。"""
    context = TaskContext.load()
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.getenv("STORAGE_GATEWAY_INTERNAL_TOKEN", ""))
    write_result(execute(context, storage))


if __name__ == "__main__":
    main()
