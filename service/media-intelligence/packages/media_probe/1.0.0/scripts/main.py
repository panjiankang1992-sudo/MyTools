#!/usr/bin/env python3
"""Probe media metadata with ffprobe and emit a bounded task result."""

from __future__ import annotations

import json
import math
import os
from pathlib import Path
import subprocess
import tempfile

MAX_OUTPUT_BYTES = 1024 * 1024


def parse_non_negative_float(value: object) -> float:
    """Parse a finite non-negative floating point value."""
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return 0.0
    return parsed if math.isfinite(parsed) and parsed >= 0 else 0.0


def parse_frame_rate(value: object) -> float:
    """Parse ffprobe rational frame rate syntax."""
    parts = str(value or "0/1").split("/", 1)
    numerator = parse_non_negative_float(parts[0])
    denominator = parse_non_negative_float(parts[1]) if len(parts) == 2 else 1.0
    return 0.0 if denominator == 0 else numerator / denominator


def normalize(parameters: dict, probe: dict) -> dict:
    """Normalize raw ffprobe JSON to the stable media probe contract."""
    streams = probe.get("streams") if isinstance(probe.get("streams"), list) else []
    video = next((item for item in streams if item.get("codec_type") == "video"), None)
    audio = next((item for item in streams if item.get("codec_type") == "audio"), None)
    media_format = probe.get("format") if isinstance(probe.get("format"), dict) else {}
    duration_ms = round(parse_non_negative_float(media_format.get("duration")) * 1000)
    if video is None and audio is None:
        raise ValueError("ffprobe output is missing media streams")
    # 静态图片没有时长，仍需生成可注册的最小探测结果。
    duration_ms = max(1, duration_ms)
    normalized_video = video or {}
    return {
        "assetId": str(parameters["assetId"]),
        "contentSha256": str(parameters["contentSha256"]).lower(),
        "format": str(media_format.get("format_name") or "unknown")[:64],
        "durationMs": duration_ms,
        "bitRate": max(0, int(parse_non_negative_float(media_format.get("bit_rate")))),
        "video": {
            "codec": str(normalized_video.get("codec_name") or "none")[:64],
            "width": max(0, int(normalized_video.get("width") or 0)),
            "height": max(0, int(normalized_video.get("height") or 0)),
            "frameRate": parse_frame_rate(normalized_video.get("avg_frame_rate")),
        },
        "audio": None if audio is None else {"codec": str(audio.get("codec_name") or "unknown")[:64]},
    }


def run_probe(source: Path) -> dict:
    """Run ffprobe without a shell and reject oversized output."""
    if not source.is_file():
        raise ValueError("media source does not exist")
    completed = subprocess.run(
        ["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(source)],
        check=True,
        capture_output=True,
        timeout=30,
    )
    if len(completed.stdout) > MAX_OUTPUT_BYTES:
        raise ValueError("ffprobe output exceeds size limit")
    return json.loads(completed.stdout.decode("utf-8"))


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def resolve_source(parameters: dict, step_outputs: dict) -> Path:
    """Resolve materialized input first while retaining the legacy task parameter fallback."""
    materialized = step_outputs.get("materialize_input")
    value = materialized.get("sourcePath") if isinstance(materialized, dict) else None
    value = value or parameters.get("sourcePath")
    if not isinstance(value, str) or not value.strip():
        raise ValueError("media source is missing")
    return Path(value)


def main() -> None:
    """Execute one media probe task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    write_result(normalize(parameters, run_probe(resolve_source(parameters, context.get("stepOutputs", {})))))


if __name__ == "__main__":
    main()
