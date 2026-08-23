#!/usr/bin/env python3
"""Generate evenly spaced JPEG storyboard frames with ffmpeg."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

DEFAULT_FRAME_COUNT = 12
MAX_FRAME_COUNT = 12


def frame_positions(duration_ms: int, count: int) -> list[int]:
    """Return bounded positions that avoid unstable exact start and end frames."""
    if duration_ms <= 0:
        raise ValueError("video duration must be positive")
    if count <= 0 or count > MAX_FRAME_COUNT:
        raise ValueError("frameCount is outside the supported range")
    return [round(duration_ms * (index + 1) / (count + 1)) for index in range(count)]


def generate_frame(source: Path, target: Path, position_ms: int, runner=subprocess.run) -> None:
    """Generate one frame without invoking a shell and publish it atomically."""
    temporary = target.with_suffix(".tmp.jpg")
    temporary.unlink(missing_ok=True)
    command = [
        "ffmpeg", "-y", "-ss", f"{position_ms / 1000:.3f}", "-i", str(source),
        "-an", "-sn", "-dn", "-frames:v", "1", "-vf",
        "scale=1280:-2:force_original_aspect_ratio=decrease:out_range=full,format=yuvj420p",
        "-q:v", "3", str(temporary),
    ]
    try:
        runner(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30)
        if not temporary.is_file() or temporary.stat().st_size <= 2:
            raise ValueError("ffmpeg produced an invalid storyboard frame")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def execute(parameters: dict, step_outputs: dict, work_directory: Path,
            frame_runner=generate_frame) -> dict:
    """Generate a storyboard using metadata from the preceding probe step."""
    source = Path(parameters["sourcePath"])
    if not source.is_file():
        raise ValueError("media source does not exist")
    probe = step_outputs.get("probe") if isinstance(step_outputs.get("probe"), dict) else {}
    duration_ms = int(probe.get("durationMs") or parameters.get("durationMs") or 0)
    count = int(parameters.get("frameCount", DEFAULT_FRAME_COUNT))
    output_directory = work_directory / "storyboard"
    output_directory.mkdir(parents=True, exist_ok=True)
    frames = []
    for index, position_ms in enumerate(frame_positions(duration_ms, count), start=1):
        target = output_directory / f"frame-{index:02d}.jpg"
        frame_runner(source, target, position_ms)
        data = target.read_bytes()
        frames.append({
            "index": index,
            "positionMs": position_ms,
            "artifactPath": str(target),
            "artifactSha256": hashlib.sha256(data).hexdigest(),
            "size": len(data),
        })
    return {
        "assetId": str(parameters["assetId"]),
        "contentSha256": str(parameters["contentSha256"]).lower(),
        "frames": frames,
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
    """Execute one storyboard generation step."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    write_result(execute(context["parameters"], context.get("stepOutputs", {}),
                         Path(os.environ["TASK_WORK_DIR"])))


if __name__ == "__main__":
    main()
