#!/usr/bin/env python3
"""在冻结章节中精确定位一条中文读音词条，并只失效命中的章节。"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request

from mytools_task_sdk.storage import StorageGatewayClient

MAX_CHAPTER_BYTES = 10 * 1024 * 1024
MAX_CHAPTERS = 20_000


class ReaderServiceClient:
    """调用 Reader Service 的读音修订内部契约。"""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Reader Service internal token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def input(self, generation_id: str) -> dict:
        """读取只含冻结章节定位与词条的准备输入。"""
        return self._request("GET", f"/{generation_id}/pronunciation-preparation-input")

    def complete(self, generation_id: str, chapter_indexes: list[int]) -> dict:
        """回写精确命中章节，不上传正文或词条证据文本。"""
        return self._request("POST", f"/{generation_id}/pronunciation-affected-chapters",
                             {"chapterIndexes": chapter_indexes})

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        payload = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers = {"Authorization": f"Bearer {self._token}", "Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(f"{self._base_url}/api/internal/v1/audiobook-generations{path}",
                                         data=payload, method=method, headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.loads(response.read().decode("utf-8"))
        if not isinstance(result, dict):
            raise RuntimeError("Reader Service pronunciation response is invalid")
        return result


def valid_term(value: object) -> str:
    """只接受受控接口已校验过的非空中文词条，防止空串匹配所有章节。"""
    if (not isinstance(value, str) or not value or len(value) > 256 or
            any(not "\u4e00" <= character <= "\u9fff" for character in value)):
        raise ValueError("Audiobook pronunciation term is invalid")
    return value


def execute(parameters: dict, storage: StorageGatewayClient, reader: ReaderServiceClient,
            work_directory: Path) -> dict:
    """校验每个冻结章节摘要后，按精确子串匹配回写最小重生成集合。"""
    generation_id = str(parameters["generationId"])
    input_data = reader.input(generation_id)
    if input_data.get("generationId") != generation_id:
        raise ValueError("Audiobook pronunciation generation input is invalid")
    term = valid_term(input_data.get("term"))
    chapters = input_data.get("chapters")
    if not isinstance(chapters, list) or not chapters or len(chapters) > MAX_CHAPTERS:
        raise ValueError("Audiobook pronunciation chapter input is invalid")
    affected: list[int] = []
    indexes: set[int] = set()
    for chapter in chapters:
        if not isinstance(chapter, dict):
            raise ValueError("Audiobook pronunciation chapter input is invalid")
        index = chapter.get("index")
        expected_hash = chapter.get("contentSha256")
        storage_uri = chapter.get("textStorageUri")
        if (not isinstance(index, int) or index < 0 or index in indexes or
                not isinstance(expected_hash, str) or len(expected_hash) != 64 or
                not isinstance(storage_uri, str) or not storage_uri):
            raise ValueError("Audiobook pronunciation chapter input is invalid")
        indexes.add(index)
        path = work_directory / f"chapter-{index:05d}.txt"
        storage.download(storage_uri, path, MAX_CHAPTER_BYTES)
        payload = path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != expected_hash.lower():
            raise ValueError("Frozen chapter text checksum does not match")
        text = payload.decode("utf-8", errors="strict")
        # 只以冻结正文的精确字符串匹配决定重跑范围，绝不采用调用方给出的章节列表。
        if term in text:
            affected.append(index)
    completed = reader.complete(generation_id, affected)
    if (completed.get("generationId") != generation_id or
            completed.get("affectedChapterCount") != len(affected)):
        raise RuntimeError("Reader Service pronunciation completion is invalid")
    return {"generationId": generation_id, "affectedChapterCount": len(affected)}


def write_result(result: dict) -> None:
    """原子写入调度器可读取的最小结果摘要。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """从部署环境加载内部端点与令牌并执行一条读音修订准备任务。"""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.environ.get("STORAGE_INTERNAL_TOKEN", ""))
    reader = ReaderServiceClient(os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                                 os.environ.get("READER_INTERNAL_TOKEN", ""))
    with tempfile.TemporaryDirectory(prefix="audiobook-pronunciation-") as directory:
        write_result(execute(context["parameters"], storage, reader, Path(directory)))


if __name__ == "__main__":
    main()
