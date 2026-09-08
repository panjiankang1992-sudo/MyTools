#!/usr/bin/env python3
"""将不可变章节音频封装为可下载、可校验的整书 ZIP。"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.request
import zipfile

from mytools_task_sdk.storage import StorageGatewayClient

MAX_CHAPTER_BYTES = 256 * 1024 * 1024
MAX_TOTAL_AUDIO_BYTES = 4 * 1024 * 1024 * 1024
MAX_CHAPTERS = 20_000
CHUNK_SIZE = 1024 * 1024
SAFE_STORAGE_URI = re.compile(r"^storage://[A-Za-z0-9][A-Za-z0-9._-]{0,127}/[^\s]+$")
SAFE_SHA256 = re.compile(r"^[a-f0-9]{64}$")


class ReaderServiceClient:
    """调用 Reader Service 的有声书导出内部契约。"""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Reader Service internal token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def input(self, export_id: str) -> dict:
        """读取已完成 generation 的不可变音频清单。"""
        return self._request("GET", f"/{export_id}/input")

    def complete(self, export_id: str, result: dict) -> dict:
        """回写已发布归档的受控摘要。"""
        return self._request("POST", f"/{export_id}/complete", result)

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        payload = None if body is None else json.dumps(body, ensure_ascii=False,
                                                       separators=(",", ":")).encode("utf-8")
        headers = {"Authorization": f"Bearer {self._token}", "Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(f"{self._base_url}/api/internal/v1/audiobook-exports{path}",
                                         data=payload, method=method, headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))


def sha256(path: Path) -> str:
    """计算一个本地文件的 SHA-256，避免一次性读取大归档。"""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_file_component(value: object, fallback: str) -> str:
    """将不可信章节标题转换为一个平坦且有界的 ZIP 文件名片段。"""
    normalized = str(value or "").replace("/", " ").replace("\\", " ").strip()
    normalized = re.sub(r'[\x00-\x1f<>:"|?*]+', " ", normalized)
    normalized = re.sub(r"\s+", " ", normalized).strip(". ")
    if not normalized:
        normalized = fallback
    return normalized[:120]


def validate_input(payload: object, export_id: str) -> tuple[str, int, list[dict]]:
    """校验服务端输入的边界、顺序和受管章节定位信息。"""
    if not isinstance(payload, dict) or str(payload.get("exportId")) != export_id:
        raise ValueError("Audiobook export input identity is invalid")
    generation_id = payload.get("generationId")
    version = payload.get("generationVersion")
    if not isinstance(generation_id, str) or not generation_id or not isinstance(version, int) or version < 1:
        raise ValueError("Audiobook export generation input is invalid")
    if payload.get("format") != "ZIP":
        raise ValueError("Audiobook export format is unsupported")
    chapters = payload.get("chapters")
    if not isinstance(chapters, list) or not chapters or len(chapters) > MAX_CHAPTERS:
        raise ValueError("Audiobook export chapter plan is invalid")
    total = 0
    expected_index = 0
    for chapter in chapters:
        if not isinstance(chapter, dict) or chapter.get("index") != expected_index:
            raise ValueError("Audiobook export chapter order is invalid")
        expected_index += 1
        storage_uri = chapter.get("storageUri")
        content_sha256 = chapter.get("contentSha256")
        size_bytes = chapter.get("sizeBytes")
        if not isinstance(storage_uri, str) or not SAFE_STORAGE_URI.fullmatch(storage_uri):
            raise ValueError("Audiobook export storage location is invalid")
        if not isinstance(content_sha256, str) or not SAFE_SHA256.fullmatch(content_sha256):
            raise ValueError("Audiobook export chapter checksum is invalid")
        if not isinstance(size_bytes, int) or size_bytes < 1 or size_bytes > MAX_CHAPTER_BYTES:
            raise ValueError("Audiobook export chapter size is invalid")
        if chapter.get("format") != "mp3":
            raise ValueError("Audiobook export chapter format is unsupported")
        total += size_bytes
        if total > MAX_TOTAL_AUDIO_BYTES:
            raise ValueError("Audiobook export total size exceeds the configured limit")
    return generation_id, version, chapters


def copy_into_archive(archive: zipfile.ZipFile, entry_name: str, source: Path) -> None:
    """以固定元数据和流式复制写入一条 ZIP 记录，保证输出可复现。"""
    info = zipfile.ZipInfo(entry_name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = 0o100644 << 16
    with source.open("rb") as input_stream, archive.open(info, "w", force_zip64=True) as output_stream:
        while chunk := input_stream.read(CHUNK_SIZE):
            output_stream.write(chunk)


def build_archive(storage: StorageGatewayClient, output_path: Path, generation_id: str, generation_version: int,
                  chapters: list[dict]) -> None:
    """下载并校验章节音频后生成不含存储定位信息的 ZIP。"""
    metadata_chapters: list[dict] = []
    with tempfile.TemporaryDirectory(dir=output_path.parent) as temporary_name:
        temporary = Path(temporary_name)
        with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            for chapter in chapters:
                index = int(chapter["index"])
                source = temporary / f"chapter-{index:05d}.mp3"
                expected_size = int(chapter["sizeBytes"])
                storage.download(str(chapter["storageUri"]), source, expected_size)
                if source.stat().st_size != expected_size:
                    raise ValueError("Audiobook export chapter size does not match frozen asset")
                if sha256(source) != str(chapter["contentSha256"]):
                    raise ValueError("Audiobook export chapter checksum does not match frozen asset")
                safe_title = safe_file_component(chapter.get("title"), "chapter")
                file_name = f"chapters/{index + 1:05d}-{safe_title}.mp3"
                copy_into_archive(archive, file_name, source)
                metadata_chapters.append({"index": index, "title": safe_title,
                                          "file": file_name, "format": "mp3"})
            metadata = {"format": "mytools-audiobook-zip-v1", "generationId": generation_id,
                        "generationVersion": generation_version, "chapters": metadata_chapters}
            info = zipfile.ZipInfo("metadata.json", date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, json.dumps(metadata, ensure_ascii=False, sort_keys=True,
                                              separators=(",", ":")).encode("utf-8"))


def execute(parameters: dict, storage: StorageGatewayClient, reader: ReaderServiceClient,
            work_directory: Path) -> dict:
    """打包一个任务绑定的 generation，并以受管对象发布结果。"""
    export_id = str(parameters["exportId"])
    storage_root = str(parameters["storageRoot"])
    if not storage_root or len(storage_root) > 128:
        raise ValueError("Audiobook export storage root is invalid")
    input_data = reader.input(export_id)
    generation_id, generation_version, chapters = validate_input(input_data, export_id)
    output_path = work_directory / "audiobook-export.zip"
    build_archive(storage, output_path, generation_id, generation_version, chapters)
    archive_sha256 = sha256(output_path)
    archive_size = output_path.stat().st_size
    if archive_size < 1:
        raise ValueError("Audiobook export archive is empty")
    storage_uri = storage.publish(output_path, storage_root,
                                  f"audiobooks/{generation_id}/exports/{export_id}-{archive_sha256}.zip",
                                  f"audiobook-export:{export_id}:{archive_sha256}", archive_size, archive_sha256)
    reader.complete(export_id, {"storageUri": storage_uri, "contentSha256": archive_sha256,
                                "sizeBytes": archive_size, "chapterCount": len(chapters)})
    return {"exportId": export_id, "chapterCount": len(chapters), "archiveContentSha256": archive_sha256,
            "archiveSizeBytes": archive_size}


def write_result(result: dict) -> None:
    """以原子替换方式写入受限任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """装配运行时客户端并执行一条整书 ZIP 导出任务。"""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.environ.get("STORAGE_INTERNAL_TOKEN", ""))
    reader = ReaderServiceClient(os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                                 os.environ.get("READER_INTERNAL_TOKEN", ""))
    write_result(execute(context["parameters"], storage, reader, context_path.parent))


if __name__ == "__main__":
    main()
