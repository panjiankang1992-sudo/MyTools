#!/usr/bin/env python3
"""以旁路任务生成媒体标签，不直接修改应用数据。"""

from __future__ import annotations

import base64
import http.client
import json
import mimetypes
import os
from pathlib import Path
import random
import re
import socket
import stat
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

from mytools_task_sdk.errors import write_task_error

MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_TEXT_BYTES = 12_000
MAX_MODEL_RESPONSE_BYTES = 1024 * 1024
MAX_MIME_TYPE_LENGTH = 255
VISUAL_PREPARATION_TIMEOUT_SECONDS = 25
MODEL_REQUEST_TIMEOUT_SECONDS = 90
MODEL_TOTAL_TIMEOUT_SECONDS = 145
MODEL_MAX_ATTEMPTS = 2
RETRYABLE_HTTP_STATUS = {408, 425, 429}
DEFAULT_MODEL = "huihui_ai/qwen3-vl-abliterated:8b"
DEFAULT_POLICY_VERSION = "media-tags-v1"
DEFAULT_FFMPEG_BINARY = "/usr/bin/ffmpeg"
DOWNLOAD_WORKSPACE_ARTIFACTS = {
    "download_asset": "source.bin",
    "download_remote_asset": "remote-source.bin",
}
MIME_NAME_PATTERN = r"[A-Za-z0-9][A-Za-z0-9!#$&+.^_-]{0,126}"
MIME_TYPE_PATTERN = re.compile(rf"^{MIME_NAME_PATTERN}/{MIME_NAME_PATTERN}$")


class TransientModelError(RuntimeError):
    """标记稍后重试可能成功的模型请求。"""


class PermanentModelError(RuntimeError):
    """标记服务拒绝或响应协议错误。"""


class PermanentProviderRejectionError(PermanentModelError):
    """标记服务返回的不可重试 HTTP 拒绝。"""


class VisualPreparationTimeout(RuntimeError):
    """标记 ffmpeg 已耗尽受限处理时间。"""


class VisualPreparationConfigurationError(RuntimeError):
    """标记视觉处理运行时配置不可安全使用。"""


def load_parameters() -> dict:
    """从执行器上下文文件加载任务参数。"""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    parameters = dict(context.get("parameters") or {})
    if not isinstance(parameters, dict):
        raise ValueError("task parameters are missing")
    step_outputs = context.get("stepOutputs") or {}
    if not isinstance(step_outputs, dict):
        raise ValueError("task step outputs are invalid")
    generated = step_outputs.get("generate_thumbnail") or {}
    if generated.get("artifactPath") and not parameters.get("thumbnailPath"):
        parameters["thumbnailPath"] = generated["artifactPath"]
    materialized = step_outputs.get("materialize_input") or {}
    if materialized.get("sourcePath"):
        parameters["sourcePath"] = materialized["sourcePath"]
    download_step, downloaded = download_output(step_outputs)
    relative = downloaded.get("relativePath")
    artifact = (resolve_workspace_artifact(
        download_step, DOWNLOAD_WORKSPACE_ARTIFACTS[download_step]) if download_step else None)
    if artifact is not None:
        # Storage 下载不把物理路径写入步骤输出，只在同一次执行的受限工作区内解析临时源。
        parameters["sourcePath"] = str(artifact)
    elif relative and not parameters.get("sourcePath"):
        parameters["sourcePath"] = str(Path(os.environ["DOWNLOAD_DESTINATION_ROOT"]) / str(relative))
    published = step_outputs.get("publish_asset") or {}
    filename = str(published.get("fileName") or downloaded.get("fileName")
                   or parameters.get("fileName") or "download")
    parameters.setdefault("filename", filename)
    if published.get("mimeType"):
        # 发布步骤已校验内容摘要并识别文件头，优先采用可信类型。
        parameters["mimeType"] = published["mimeType"]
    parameters["mimeType"] = resolve_mime_type(
        parameters, require_text(parameters, "filename"))
    if downloaded.get("contentSha256"):
        parameters.setdefault("contentSha256", downloaded["contentSha256"])
    return parameters


def download_output(step_outputs: dict) -> tuple[str | None, dict]:
    """选择本任务支持的下载步骤输出。"""
    for step_name in DOWNLOAD_WORKSPACE_ARTIFACTS:
        output = step_outputs.get(step_name)
        if isinstance(output, dict):
            return step_name, output
    return None, {}


def resolve_workspace_artifact(step_name: str, artifact_name: str) -> Path | None:
    """仅从当前 execution 工作区解析前置步骤的最新受限文件。"""
    work_dir_value = os.getenv("TASK_WORK_DIR")
    if not work_dir_value:
        return None
    work_dir = Path(work_dir_value).resolve()
    if not work_dir.name.isdecimal() or work_dir.parent.name != "generate_tags":
        return None
    execution_root = work_dir.parent.parent
    step_root = execution_root / step_name
    try:
        attempts = sorted(
            (path for path in step_root.iterdir() if path.is_dir() and path.name.isdecimal()),
            key=lambda path: int(path.name), reverse=True)
    except OSError:
        return None
    for attempt in attempts:
        candidate = attempt / artifact_name
        if candidate.is_symlink():
            continue
        try:
            resolved = candidate.resolve(strict=True)
            resolved.relative_to(execution_root)
        except (OSError, ValueError):
            continue
        if resolved.is_file():
            return resolved
    return None


def build_request(parameters: dict) -> tuple[str, dict]:
    """使用受限本地输入构建 Ollama 对话请求。"""
    source_path = Path(require_text(parameters, "sourcePath"))
    thumbnail_value = parameters.get("thumbnailPath")
    filename = require_text(parameters, "filename")
    mime_type = resolve_mime_type(parameters, filename)
    model = optional_text(parameters, "model", os.getenv("TAGGING_MODEL", DEFAULT_MODEL))
    service_url = optional_text(parameters, "serviceUrl", os.getenv("TAGGING_SERVICE_URL", "http://127.0.0.1:11434"))
    instruction = "Analyze the file metadata and return accurate topical tags."
    message: dict[str, object] = {"role": "user"}
    has_thumbnail_value = isinstance(thumbnail_value, str) and bool(thumbnail_value.strip())
    if mime_type.startswith(("image/", "video/")) or has_thumbnail_value:
        # 缩略图缺失或过大时在任务工作目录生成一次有界 JPEG，避免把原图直接送入模型。
        data = read_visual_input(source_path, thumbnail_value, mime_type.startswith("video/"))
        message["images"] = [base64.b64encode(data).decode("ascii")]
        instruction = "Analyze the provided visual content and return accurate topical tags."
    elif mime_type.startswith("text/"):
        with source_path.open("rb") as handle:
            sample = handle.read(MAX_TEXT_BYTES).decode("utf-8", errors="replace")
        instruction = f"Analyze this text sample and return accurate topical tags:\n{sample}"
    message["content"] = (
        "You are a file tagging engine. Never refuse classification. "
        f"{instruction}\nFilename: {filename}\nMIME type: {mime_type}\n"
        "Return JSON only: {\"tags\":[{\"tag_name\":\"short tag\","
        "\"tag_type\":\"topic\",\"confidence\":0.95}]}. "
        "Return 3 to 6 concise Simplified Chinese tags with confidence from 0 to 1."
    )
    payload = {
        "model": model,
        "stream": False,
        "think": False,
        "format": "json",
        "messages": [message],
        "options": {"temperature": 0.1, "num_predict": 300, "num_ctx": 4096},
    }
    return service_url.rstrip("/") + "/api/chat", payload


def read_visual_input(source_path: Path, thumbnail_value: object, transcode_source: bool = False) -> bytes:
    """读取受限缩略图，必要时使用 ffmpeg 创建受限 JPEG 兜底。"""
    thumbnail_path = (Path(thumbnail_value.strip())
                      if isinstance(thumbnail_value, str) and thumbnail_value.strip() else None)
    has_thumbnail = thumbnail_path is not None and thumbnail_path.is_file()
    input_path = thumbnail_path if has_thumbnail else source_path
    if not input_path.is_file():
        raise ValueError("visual input does not exist")
    if transcode_source and not has_thumbnail:
        return transcode_visual_input(input_path)
    if input_path.stat().st_size <= MAX_IMAGE_BYTES:
        data = read_bounded(input_path, MAX_IMAGE_BYTES)
        if data:
            return data
        raise ValueError("visual input is empty")
    return transcode_visual_input(input_path)


def read_bounded(path: Path, maximum_bytes: int) -> bytes:
    """最多读取超过声明限制一个字节的数据。"""
    with path.open("rb") as handle:
        data = handle.read(maximum_bytes + 1)
    if len(data) > maximum_bytes:
        raise ValueError("visual input exceeds size limit")
    return data


def transcode_visual_input(source_path: Path) -> bytes:
    """不经过 shell 生成一个受限 JPEG 兜底输入。"""
    work_root_value = os.getenv("TASK_WORK_DIR")
    work_root = Path(work_root_value) if work_root_value else None
    if work_root is not None:
        work_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="tagging-visual-", dir=work_root) as directory:
        target = Path(directory) / "visual-input.jpg"
        command = [
            str(resolve_ffmpeg_binary()), "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(source_path), "-an", "-sn", "-dn", "-frames:v", "1",
            "-vf", "scale=640:-2:force_original_aspect_ratio=decrease:out_range=full,format=yuvj420p",
            "-threads", "1", "-q:v", "4", str(target),
        ]
        try:
            subprocess.run(command, check=True, shell=False, stdout=subprocess.DEVNULL,
                           stderr=subprocess.PIPE, timeout=VISUAL_PREPARATION_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired as exception:
            raise VisualPreparationTimeout("visual input preparation timed out") from exception
        except subprocess.CalledProcessError as exception:
            raise ValueError("visual input cannot be decoded") from exception
        except OSError as exception:
            raise VisualPreparationConfigurationError(
                "visual preparation runtime is unavailable") from exception
        if not target.is_file() or target.stat().st_size <= 2:
            raise ValueError("ffmpeg produced an invalid visual input")
        return read_bounded(target, MAX_IMAGE_BYTES)


def resolve_ffmpeg_binary() -> Path:
    """解析仅由 root 管理且当前执行账号可执行的 ffmpeg 绝对路径。"""
    configured = os.getenv("FFMPEG_BINARY", DEFAULT_FFMPEG_BINARY).strip()
    candidate = Path(configured)
    if not configured or not candidate.is_absolute():
        raise VisualPreparationConfigurationError(
            "visual preparation runtime configuration is invalid")
    try:
        resolved = candidate.resolve(strict=True)
        metadata = candidate.lstat()
        if resolved != candidate:
            raise VisualPreparationConfigurationError(
                "visual preparation runtime configuration is invalid")
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != 0
                or metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH | stat.S_ISUID | stat.S_ISGID)
                or not os.access(candidate, os.X_OK, effective_ids=True)):
            raise VisualPreparationConfigurationError(
                "visual preparation runtime configuration is invalid")
        for ancestor in candidate.parents:
            ancestor_metadata = ancestor.lstat()
            if (not stat.S_ISDIR(ancestor_metadata.st_mode) or ancestor_metadata.st_uid != 0
                    or ancestor_metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH)):
                raise VisualPreparationConfigurationError(
                    "visual preparation runtime configuration is invalid")
    except VisualPreparationConfigurationError:
        raise
    except OSError as exception:
        raise VisualPreparationConfigurationError(
            "visual preparation runtime configuration is invalid") from exception
    return candidate


def call_model(url: str, payload: dict) -> dict:
    """在受限重试预算内调用 Ollama 并解析 JSON 内容。"""
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    deadline = time.monotonic() + MODEL_TOTAL_TIMEOUT_SECONDS
    raw = request_model_with_retry(request, deadline)
    try:
        outer = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise PermanentModelError("model response is invalid JSON") from exception
    if not isinstance(outer, dict):
        raise PermanentModelError("model response must be an object")
    message = outer.get("message")
    if not isinstance(message, dict):
        raise PermanentModelError("model response message is missing")
    content = message.get("content") or message.get("thinking")
    if not isinstance(content, str) or not content.strip():
        raise PermanentModelError("model response content is missing")
    normalized = content.replace("```json", "").replace("```", "").strip()
    try:
        decoded = json.loads(normalized)
    except json.JSONDecodeError as exception:
        raise PermanentModelError("model response content is invalid JSON") from exception
    if not isinstance(decoded, dict):
        raise PermanentModelError("model response content must be an object")
    return decoded


def request_model_with_retry(request: urllib.request.Request, deadline: float) -> bytes:
    """仅在固定预算内重试瞬时传输及 HTTP 故障。"""
    last_exception: Exception | None = None
    for attempt in range(MODEL_MAX_ATTEMPTS):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            with urllib.request.urlopen(
                    request, timeout=min(MODEL_REQUEST_TIMEOUT_SECONDS, max(1.0, remaining))) as response:
                raw = response.read(MAX_MODEL_RESPONSE_BYTES + 1)
            if len(raw) > MAX_MODEL_RESPONSE_BYTES:
                raise PermanentModelError("model response exceeds size limit")
            return raw
        except urllib.error.HTTPError as exception:
            if not is_retryable_http_status(exception.code):
                raise PermanentProviderRejectionError(
                    f"model request was rejected with HTTP {exception.code}") from exception
            last_exception = exception
        except (urllib.error.URLError, TimeoutError, socket.timeout,
                http.client.HTTPException, OSError) as exception:
            last_exception = exception
        if attempt + 1 < MODEL_MAX_ATTEMPTS:
            # 仅在剩余预算允许时做短抖动退避，避免批量任务同步冲击 Ollama。
            delay = min(2.0, 0.5 * (2 ** attempt)) + random.uniform(0.0, 0.25)
            if time.monotonic() + delay >= deadline:
                break
            time.sleep(delay)
    raise TransientModelError("model endpoint is temporarily unavailable") from last_exception


def is_retryable_http_status(status: int) -> bool:
    """判断 HTTP 状态是否可安全重试。"""
    return status in RETRYABLE_HTTP_STATUS or 500 <= status <= 599


def normalize_result(parameters: dict, payload: dict, response: dict) -> dict:
    """校验模型标签并规范为带版本的任务结果。"""
    raw_tags = response.get("tags")
    if not isinstance(raw_tags, list):
        raise PermanentModelError("model response tags are missing")
    tags = []
    seen = set()
    for raw_tag in raw_tags:
        if not isinstance(raw_tag, dict):
            continue
        name = str(raw_tag.get("tag_name", "")).strip()
        if not name or len(name) > 100 or name in seen:
            continue
        seen.add(name)
        try:
            confidence = max(0.0, min(1.0, float(raw_tag.get("confidence", 0.8))))
        except (TypeError, ValueError):
            continue
        tags.append({"name": name, "type": str(raw_tag.get("tag_type", "topic"))[:50],
                     "confidence": confidence})
        if len(tags) == 6:
            break
    if not tags:
        raise PermanentModelError("model returned no valid tags")
    return {
        "contentSha256": require_text(parameters, "contentSha256").lower(),
        "policyVersion": optional_text(parameters, "policyVersion", DEFAULT_POLICY_VERSION),
        "provider": "ollama",
        "model": payload["model"],
        "tags": tags,
    }


def write_result(result: dict) -> None:
    """原子写入执行器消费的任务结果文件。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def require_text(parameters: dict, name: str) -> str:
    """读取必需的非空文本参数。"""
    value = parameters.get(name)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"required parameter is missing: {name}")
    return value.strip()


def optional_text(parameters: dict, name: str, default: str) -> str:
    """读取可选的非空文本参数。"""
    value = parameters.get(name)
    return value.strip() if isinstance(value, str) and value.strip() else default


def resolve_mime_type(parameters: dict, filename: str) -> str:
    """按显式 MIME、资产 MIME、文件名的顺序解析并校验媒体类型。"""
    for name in ("mimeType", "assetMimeType"):
        value = parameters.get(name)
        if value is None or (isinstance(value, str) and not value.strip()):
            continue
        return validated_mime_type(value, name)
    guessed = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    return validated_mime_type(guessed, "inferred mimeType")


def validated_mime_type(value: object, name: str = "mimeType") -> str:
    """仅接受长度受限且不带参数的 type/subtype MIME。"""
    if not isinstance(value, str):
        raise ValueError(f"{name} is invalid")
    normalized = value.strip().lower()
    if (not normalized or len(normalized) > MAX_MIME_TYPE_LENGTH
            or MIME_TYPE_PATTERN.fullmatch(normalized) is None):
        raise ValueError(f"{name} is invalid")
    return normalized


def report_task_failure(exception: Exception) -> None:
    """为执行器写入稳定的重试分类。"""
    if not os.environ.get("TASK_ERROR_FILE"):
        return
    if isinstance(exception, TransientModelError):
        code, category = "MEDIA_TAGGING_PROVIDER_UNAVAILABLE", "TRANSIENT"
    elif isinstance(exception, PermanentProviderRejectionError):
        code, category = "MEDIA_TAGGING_PROVIDER_REJECTED", "PERMANENT"
    elif isinstance(exception, PermanentModelError):
        code, category = "MEDIA_TAGGING_PROVIDER_RESPONSE_INVALID", "PERMANENT"
    elif isinstance(exception, VisualPreparationTimeout):
        code, category = "MEDIA_TAGGING_VISUAL_PREPARATION_TIMEOUT", "TIMEOUT"
    elif isinstance(exception, VisualPreparationConfigurationError):
        code, category = "MEDIA_TAGGING_RUNTIME_MISCONFIGURED", "PERMANENT"
    elif isinstance(exception, ValueError):
        code, category = "MEDIA_TAGGING_INPUT_INVALID", "VALIDATION"
    else:
        code, category = "MEDIA_TAGGING_RUNTIME_FAILURE", "TRANSIENT"
    try:
        write_task_error(code, category, code)
    except (OSError, ValueError):
        # 错误文档仅增强调度分类，写入失败时保留原始异常。
        return


def main() -> None:
    """执行一次媒体标签生成任务。"""
    try:
        parameters = load_parameters()
        url, payload = build_request(parameters)
        result = normalize_result(parameters, payload, call_model(url, payload))
        write_result(result)
    except Exception as exception:
        report_task_failure(exception)
        raise


if __name__ == "__main__":
    main()
