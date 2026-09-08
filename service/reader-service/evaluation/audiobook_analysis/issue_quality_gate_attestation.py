#!/usr/bin/env python3
"""Create a compact auditable attestation before enabling multi-character audiobook voices."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import stat
import sys
import tempfile


def resolve_sdk_root() -> Path:
    """从源码树或发布包内定位质量门禁证明所需 SDK。"""
    for ancestor in Path(__file__).resolve().parents:
        for candidate in (ancestor / "task-executor-sdk",
                          ancestor / "service" / "task-executor-service" / "sdk" / "python"):
            if candidate.is_dir():
                return candidate
    raise RuntimeError("Audiobook quality gate SDK dependency is unavailable")


SDK_ROOT = resolve_sdk_root()
if str(SDK_ROOT) not in sys.path:
    sys.path.insert(0, str(SDK_ROOT))

from mytools_task_sdk.audiobook_quality_gate import build_attestation


MAX_RECORD_BYTES = 2 * 1024 * 1024


def read_record(path: Path, field: str) -> dict:
    """Read one bounded, regular, UTF-8 JSON validation record without evaluating any shell data."""
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"Audiobook quality gate {field} file is invalid")
    payload = path.read_bytes()
    if not payload or len(payload) > MAX_RECORD_BYTES:
        raise ValueError(f"Audiobook quality gate {field} file is invalid")
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ValueError(f"Audiobook quality gate {field} file is invalid") from exception
    if not isinstance(value, dict):
        raise ValueError(f"Audiobook quality gate {field} file is invalid")
    return value


def write_attestation(path: Path, attestation: dict) -> None:
    """Atomically create a private attestation file and never overwrite an existing audit artifact."""
    if path.exists() or path.is_symlink():
        raise ValueError("Audiobook quality gate attestation output already exists")
    if not path.parent.is_dir():
        raise ValueError("Audiobook quality gate attestation output directory is unavailable")
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            json.dump(attestation, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            handle.write("\n")
            handle.flush()
            os.fchmod(handle.fileno(), stat.S_IRUSR | stat.S_IWUSR)
        temporary.replace(path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def parse_arguments(argv: list[str] | None = None) -> argparse.Namespace:
    """Parse explicit local evidence record paths for a no-network attestation operation."""
    parser = argparse.ArgumentParser(description="Issue a MyTools audiobook multi-character quality-gate attestation")
    parser.add_argument("--evaluation-report", required=True, type=Path)
    parser.add_argument("--ner-verification", required=True, type=Path)
    parser.add_argument("--cross-chapter-review", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    """Validate three pre-existing audit records and create their nonsecret integrity-bound summary."""
    arguments = parse_arguments(argv)
    attestation = build_attestation(
        read_record(arguments.evaluation_report, "evaluation report"),
        read_record(arguments.ner_verification, "NER verification"),
        read_record(arguments.cross_chapter_review, "cross-chapter review"))
    write_attestation(arguments.output, attestation)
    print(json.dumps({"issued": True, "attestationSha256": attestation["attestationSha256"]},
                     ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
