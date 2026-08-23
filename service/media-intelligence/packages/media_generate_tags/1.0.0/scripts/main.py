#!/usr/bin/env python3
"""Generate media tags as a sidecar task without mutating application data."""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import tempfile
import urllib.request

MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_TEXT_BYTES = 12_000
DEFAULT_MODEL = "huihui_ai/qwen3-vl-abliterated:4b"
DEFAULT_POLICY_VERSION = "media-tags-v1"


def load_parameters() -> dict:
    """Load task parameters from the executor context file."""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    parameters = context.get("parameters")
    if not isinstance(parameters, dict):
        raise ValueError("task parameters are missing")
    return parameters


def build_request(parameters: dict) -> tuple[str, dict]:
    """Build an Ollama chat request from bounded local input."""
    source_path = Path(require_text(parameters, "sourcePath"))
    thumbnail_value = parameters.get("thumbnailPath")
    input_path = Path(thumbnail_value) if isinstance(thumbnail_value, str) and thumbnail_value else source_path
    mime_type = require_text(parameters, "mimeType")
    filename = require_text(parameters, "filename")
    model = optional_text(parameters, "model", os.getenv("TAGGING_MODEL", DEFAULT_MODEL))
    service_url = optional_text(parameters, "serviceUrl", os.getenv("TAGGING_SERVICE_URL", "http://127.0.0.1:11434"))
    instruction = "Analyze the file metadata and return accurate topical tags."
    message: dict[str, object] = {"role": "user"}
    if mime_type.startswith("image/") or thumbnail_value:
        data = input_path.read_bytes()
        if len(data) > MAX_IMAGE_BYTES:
            raise ValueError("visual input exceeds size limit")
        message["images"] = [base64.b64encode(data).decode("ascii")]
        instruction = "Analyze the provided visual content and return accurate topical tags."
    elif mime_type.startswith("text/"):
        sample = input_path.read_bytes()[:MAX_TEXT_BYTES].decode("utf-8", errors="replace")
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


def call_model(url: str, payload: dict) -> dict:
    """Call Ollama and decode its JSON message content."""
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        outer = json.loads(response.read().decode("utf-8"))
    message = outer.get("message")
    if not isinstance(message, dict):
        raise ValueError("model response message is missing")
    content = message.get("content") or message.get("thinking")
    if not isinstance(content, str) or not content.strip():
        raise ValueError("model response content is missing")
    normalized = content.replace("```json", "").replace("```", "").strip()
    return json.loads(normalized)


def normalize_result(parameters: dict, payload: dict, response: dict) -> dict:
    """Validate and normalize model tags into the versioned task result."""
    raw_tags = response.get("tags")
    if not isinstance(raw_tags, list):
        raise ValueError("model response tags are missing")
    tags = []
    seen = set()
    for raw_tag in raw_tags:
        if not isinstance(raw_tag, dict):
            continue
        name = str(raw_tag.get("tag_name", "")).strip()
        if not name or len(name) > 100 or name in seen:
            continue
        seen.add(name)
        confidence = max(0.0, min(1.0, float(raw_tag.get("confidence", 0.8))))
        tags.append({"name": name, "type": str(raw_tag.get("tag_type", "topic"))[:50],
                     "confidence": confidence})
        if len(tags) == 6:
            break
    if not tags:
        raise ValueError("model returned no valid tags")
    return {
        "contentSha256": require_text(parameters, "contentSha256").lower(),
        "policyVersion": optional_text(parameters, "policyVersion", DEFAULT_POLICY_VERSION),
        "provider": "ollama",
        "model": payload["model"],
        "tags": tags,
    }


def write_result(result: dict) -> None:
    """Atomically write the task result file consumed by the executor."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def require_text(parameters: dict, name: str) -> str:
    """Read a required non-blank text parameter."""
    value = parameters.get(name)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"required parameter is missing: {name}")
    return value.strip()


def optional_text(parameters: dict, name: str, default: str) -> str:
    """Read an optional non-blank text parameter."""
    value = parameters.get(name)
    return value.strip() if isinstance(value, str) and value.strip() else default


def main() -> None:
    """Execute one media tag generation task."""
    parameters = load_parameters()
    url, payload = build_request(parameters)
    result = normalize_result(parameters, payload, call_model(url, payload))
    write_result(result)


if __name__ == "__main__":
    main()
