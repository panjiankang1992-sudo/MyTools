#!/usr/bin/env python3
"""Generate a JPEG thumbnail inside the executor work directory."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def generate(source: Path, target: Path, seek_seconds: float) -> None:
    """Generate one bounded thumbnail using ffmpeg without invoking a shell."""
    if not source.is_file():
        raise ValueError("media source does not exist")
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".tmp.jpg")
    temporary.unlink(missing_ok=True)
    command = ["ffmpeg", "-y"]
    if seek_seconds > 0:
        command.extend(["-ss", str(seek_seconds)])
    command.extend([
        "-i", str(source), "-an", "-sn", "-dn", "-frames:v", "1", "-vf",
        "scale=640:-2:force_original_aspect_ratio=decrease:out_range=full,format=yuvj420p",
        "-q:v", "3", str(temporary),
    ])
    try:
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30)
        if not temporary.is_file() or temporary.stat().st_size <= 2:
            raise ValueError("ffmpeg produced an invalid thumbnail")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def build_result(parameters: dict, target: Path) -> dict:
    """Build the immutable thumbnail artifact result."""
    data = target.read_bytes()
    return {
        "assetId": str(parameters["assetId"]),
        "contentSha256": str(parameters["contentSha256"]).lower(),
        "artifactPath": str(target),
        "artifactSha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
    }


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def resolve_source(parameters: dict, step_outputs: dict) -> Path:
    """Resolve the durable materialized input with a legacy parameter fallback."""
    materialized = step_outputs.get("materialize_input")
    value = materialized.get("sourcePath") if isinstance(materialized, dict) else None
    downloaded = step_outputs.get("download_asset")
    relative = downloaded.get("relativePath") if isinstance(downloaded, dict) else None
    if not value and isinstance(relative, str) and relative.strip():
        value = str(Path(os.environ["DOWNLOAD_DESTINATION_ROOT"]) / relative)
    value = value or parameters.get("sourcePath")
    if not isinstance(value, str) or not value.strip():
        raise ValueError("media source is missing")
    return Path(value)


def main() -> None:
    """Execute one thumbnail generation task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = dict(context["parameters"])
    outputs = context.get("stepOutputs", {})
    downloaded = outputs.get("download_asset") or {}
    registered = outputs.get("register_asset") or {}
    if not parameters.get("assetId"):
        parameters["assetId"] = registered.get("assetId") or parameters.get("itemId")
    if not parameters.get("contentSha256"):
        parameters["contentSha256"] = downloaded.get("contentSha256")
    work_directory = Path(os.environ["TASK_WORK_DIR"])
    target = work_directory / "thumbnail.jpg"
    generate(resolve_source(parameters, outputs), target,
             float(parameters.get("seekSeconds", 1)))
    write_result(build_result(parameters, target))


if __name__ == "__main__":
    main()
