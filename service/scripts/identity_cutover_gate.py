#!/usr/bin/env python3
"""校验冻结的 Identity 用户迁移证据，不改变运行状态。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

MAXIMUM_REPORT_BYTES = 1024 * 1024
DIGEST = re.compile(r"^[a-f0-9]{64}$")
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


def read_report(path: Path) -> dict:
    """读取一个有界的本地证据文档。"""
    if not path.is_file() or path.stat().st_size > MAXIMUM_REPORT_BYTES:
        raise ValueError("EVIDENCE_FILE_INVALID")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("EVIDENCE_FILE_INVALID")
    return value


def evaluate(dry_run: dict, applied: dict, replay: dict, target: dict) -> dict:
    """评估来源冻结、计数闭合、重放和目标对账。"""
    errors: set[str] = set()
    reports = (dry_run, applied, replay)
    migration_key = safe_text(dry_run.get("migrationKey"))
    if not MIGRATION_KEY.fullmatch(migration_key) \
            or any(safe_text(report.get("migrationKey")) != migration_key
                   for report in reports) \
            or safe_text(target.get("migrationKey")) != migration_key:
        errors.add("MIGRATION_KEY_MISMATCH")
    if dry_run.get("dryRun") is not True or applied.get("dryRun") is not False \
            or replay.get("dryRun") is not False:
        errors.add("MIGRATION_MODE_INVALID")

    high_water = integer(dry_run, "sourceHighWater", errors)
    source_count = integer(dry_run, "sourceItemCount", errors)
    source_digest = safe_text(dry_run.get("sourceDigestSha256"))
    if DIGEST.fullmatch(source_digest) is None:
        errors.add("SOURCE_EVIDENCE_INVALID")
    for report in reports:
        if integer(report, "sourceHighWater", errors) != high_water \
                or integer(report, "sourceItemCount", errors) != source_count \
                or safe_text(report.get("sourceDigestSha256")) != source_digest:
            errors.add("SOURCE_EVIDENCE_MISMATCH")
        if safe_text(report.get("digestSha256")) != source_digest:
            errors.add("MIGRATION_DIGEST_MISMATCH")

    counts = [migration_counts(report, errors) for report in reports]
    dry_counts, apply_counts, replay_counts = counts
    if dry_counts != apply_counts:
        errors.add("DRY_RUN_APPLY_MISMATCH")
    if any(value[0] != source_count or sum(value[1:]) != value[0] for value in counts):
        errors.add("MIGRATION_COUNT_MISMATCH")
    if any(value[3] != 0 for value in counts):
        errors.add("MIGRATION_HAS_REJECTIONS")
    if replay_counts[1] != 0 or replay_counts[2] != source_count:
        errors.add("REPLAY_NOT_IDEMPOTENT")

    target_count = integer(target, "itemCount", errors)
    target_digest = safe_text(target.get("collectionSha256"))
    if target_count != source_count:
        errors.add("TARGET_COUNT_MISMATCH")
    if DIGEST.fullmatch(target_digest) is None or target_digest != source_digest:
        errors.add("TARGET_DIGEST_MISMATCH")
    return {"ready": not errors, "migrationKey": migration_key or None,
            "migratedCount": target_count, "errors": sorted(errors)}


def migration_counts(report: dict, errors: set[str]) -> tuple[int, int, int, int]:
    """读取导出、接受、跳过和拒绝计数。"""
    return tuple(integer(report, name, errors)
                 for name in ("exported", "accepted", "skipped", "rejected"))


def integer(report: dict, name: str, errors: set[str]) -> int:
    """读取非负整数且不接受布尔值。"""
    value = report.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        errors.add("EVIDENCE_REPORT_INVALID")
        return -1
    return value


def safe_text(value: object) -> str:
    """返回有界证据文本，不序列化用户数据。"""
    return value if isinstance(value, str) and len(value) <= 255 else ""


def main() -> None:
    """运行只读 Identity 切换证据门禁。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run-report", required=True, type=Path)
    parser.add_argument("--apply-report", required=True, type=Path)
    parser.add_argument("--replay-report", required=True, type=Path)
    parser.add_argument("--target-report", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        result = evaluate(read_report(arguments.dry_run_report),
                          read_report(arguments.apply_report),
                          read_report(arguments.replay_report),
                          read_report(arguments.target_report))
    except (OSError, ValueError, json.JSONDecodeError):
        result = {"ready": False, "migrationKey": None, "migratedCount": -1,
                  "errors": ["EVIDENCE_FILE_INVALID"]}
    print(json.dumps(result, separators=(",", ":")))
    if not result["ready"]:
        sys.exit(2)


if __name__ == "__main__":
    main()
