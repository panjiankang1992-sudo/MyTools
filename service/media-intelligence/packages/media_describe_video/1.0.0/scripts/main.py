#!/usr/bin/env python3
"""Generate a bounded video description from probe metadata and storyboard frames."""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen

MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_FRAMES = 12


def storyboard_paths(step_outputs: dict) -> list[Path]:
    """Extract validated storyboard artifact paths from the preceding step."""
    storyboard = step_outputs.get("generate_storyboard")
    frames = storyboard.get("frames", []) if isinstance(storyboard, dict) else []
    paths = []
    for frame in frames[:MAX_FRAMES]:
        path = Path(str(frame.get("artifactPath", "")))
        if path.is_file() and 0 < path.stat().st_size <= MAX_IMAGE_BYTES:
            paths.append(path)
    return paths


def fallback_description(parameters: dict, probe: dict, frame_count: int) -> tuple[str, str]:
    """Build a deterministic description when model inference is disabled or unavailable."""
    source_name = Path(str(parameters["sourcePath"])).name
    duration_seconds = round(int(probe.get("durationMs") or 0) / 1000)
    video = probe.get("video") if isinstance(probe.get("video"), dict) else {}
    resolution = f"{int(video.get('width') or 0)}x{int(video.get('height') or 0)}"
    summary = f"Video {source_name}"[:200]
    description = (
        f"Metadata-derived description for {source_name}. Duration is {duration_seconds} seconds, "
        f"resolution is {resolution}, and {frame_count} evenly sampled storyboard frames are available."
    )
    return summary, description[:2000]


def model_description(parameters: dict, probe: dict, paths: list[Path], opener=urlopen) -> tuple[str, str]:
    """Call an OpenAI-compatible chat endpoint and validate its JSON response."""
    base_url = os.environ.get("MEDIA_DESCRIPTION_BASE_URL", "").rstrip("/")
    if not base_url:
        raise ValueError("media description model is disabled")
    content = [{
        "type": "text",
        "text": (
            "Describe this video in Simplified Chinese. Return JSON with summary and description. "
            f"Keep summary within 200 characters and description within 2000. Metadata: {json.dumps(probe)}"
        ),
    }]
    for path in paths:
        encoded = base64.b64encode(path.read_bytes()).decode("ascii")
        content.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{encoded}"}})
    payload = {
        "model": os.environ.get("MEDIA_DESCRIPTION_MODEL", "qwen-vl"),
        "messages": [{"role": "user", "content": content}],
        "response_format": {"type": "json_object"},
        "temperature": 0.2,
    }
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    api_key = os.environ.get("MEDIA_DESCRIPTION_API_KEY", "")
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = Request(f"{base_url}/chat/completions", data=json.dumps(payload).encode(), headers=headers)
    with opener(request, timeout=180) as response:
        document = json.loads(response.read().decode("utf-8"))
    raw_content = document["choices"][0]["message"]["content"]
    result = json.loads(raw_content) if isinstance(raw_content, str) else raw_content
    summary = " ".join(str(result.get("summary") or "").split())[:200]
    description = " ".join(str(result.get("description") or "").split())[:2000]
    if not summary or not description:
        raise ValueError("model response is missing summary or description")
    return summary, description


def execute(parameters: dict, step_outputs: dict) -> dict:
    """Generate a model description with deterministic metadata fallback."""
    probe = step_outputs.get("probe") if isinstance(step_outputs.get("probe"), dict) else {}
    paths = storyboard_paths(step_outputs)
    try:
        summary, description = model_description(parameters, probe, paths)
        mode = "MODEL"
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        summary, description = fallback_description(parameters, probe, len(paths))
        mode = "METADATA_FALLBACK"
    return {
        "assetId": str(parameters["assetId"]),
        "contentSha256": str(parameters["contentSha256"]).lower(),
        "summary": summary,
        "description": description,
        "generationMode": mode,
        "frameCount": len(paths),
    }


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one video description step."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    write_result(execute(context["parameters"], context.get("stepOutputs", {})))


if __name__ == "__main__":
    main()
