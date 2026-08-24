#!/usr/bin/env python3
"""Validate Reader migration evidence without changing runtime state."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys


MAXIMUM_REPORT_BYTES = 1024 * 1024
DIGEST = re.compile(r"^[a-f0-9]{64}$")
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
ENTITY_TYPES = ("SHELF", "PROGRESS", "MARKER")


def read_report(path: Path) -> dict:
    """Read one bounded local evidence document."""

    if not path.is_file() or path.stat().st_size > MAXIMUM_REPORT_BYTES:
        raise ValueError("EVIDENCE_FILE_INVALID")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("EVIDENCE_FILE_INVALID")
    return value


def evaluate(dry_run: dict, applied: dict, replay: dict, target: dict) -> dict:
    """Evaluate frozen source, idempotent replay, and target equality."""

    errors: set[str] = set()
    reports = (dry_run, applied, replay)
    migration_key = safe_text(dry_run.get("migrationKey"))
    if not MIGRATION_KEY.fullmatch(migration_key) \
            or any(safe_text(report.get("migrationKey")) != migration_key for report in reports) \
            or safe_text(target.get("migrationKey")) != migration_key:
        errors.add("MIGRATION_KEY_MISMATCH")
    if dry_run.get("dryRun") is not True or applied.get("dryRun") is not False \
            or replay.get("dryRun") is not False:
        errors.add("MIGRATION_MODE_INVALID")
    high_water = read_high_water(dry_run, errors)
    source_count = integer(dry_run, "exported", errors)
    source_digest = safe_text(dry_run.get("digestSha256"))
    if DIGEST.fullmatch(source_digest) is None:
        errors.add("SOURCE_EVIDENCE_INVALID")
    for report in reports:
        if read_high_water(report, errors) != high_water \
                or integer(report, "exported", errors) != source_count \
                or safe_text(report.get("digestSha256")) != source_digest:
            errors.add("SOURCE_EVIDENCE_MISMATCH")
    counts = [migration_counts(report, errors) for report in reports]
    if counts[0] != counts[1]:
        errors.add("DRY_RUN_APPLY_MISMATCH")
    if any(sum(values[1:]) != values[0] for values in counts):
        errors.add("MIGRATION_COUNT_MISMATCH")
    if any(values[3] != 0 for values in counts):
        errors.add("MIGRATION_HAS_REJECTIONS")
    if counts[2][1] != 0 or counts[2][2] != source_count:
        errors.add("REPLAY_NOT_IDEMPOTENT")
    target_count = integer(target, "itemCount", errors)
    target_digest = safe_text(target.get("digestSha256"))
    if target_count != source_count:
        errors.add("TARGET_COUNT_MISMATCH")
    if DIGEST.fullmatch(target_digest) is None or target_digest != source_digest:
        errors.add("TARGET_DIGEST_MISMATCH")
    return {"ready": not errors, "migrationKey": migration_key or None,
            "migratedCount": target_count, "sourceHighWater": high_water,
            "errors": sorted(errors)}


def migration_counts(report: dict, errors: set[str]) -> tuple[int, int, int, int]:
    """Read exported, accepted, skipped, and rejected counts."""

    return tuple(integer(report, name, errors)
                 for name in ("exported", "accepted", "skipped", "rejected"))


def read_high_water(report: dict, errors: set[str]) -> dict | None:
    """Read the exact three-entity composite cursor map."""

    value = report.get("sourceHighWater")
    if not isinstance(value, dict) or set(value) != set(ENTITY_TYPES):
        errors.add("SOURCE_HIGH_WATER_INVALID")
        return None
    result = {}
    for entity_type in ENTITY_TYPES:
        cursor = value.get(entity_type)
        if not isinstance(cursor, dict) or set(cursor) != {"ownerId", "key"} \
                or not isinstance(cursor["ownerId"], int) or isinstance(cursor["ownerId"], bool) \
                or cursor["ownerId"] < 0 or not isinstance(cursor["key"], str) \
                or len(cursor["key"]) > 1000:
            errors.add("SOURCE_HIGH_WATER_INVALID")
            return None
        result[entity_type] = dict(cursor)
    return result


def integer(report: dict, name: str, errors: set[str]) -> int:
    """Read a non-negative integer without accepting booleans."""

    value = report.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        errors.add("EVIDENCE_REPORT_INVALID")
        return -1
    return value


def safe_text(value: object) -> str:
    """Return bounded scalar evidence text."""

    return value if isinstance(value, str) and len(value) <= 255 else ""


def main() -> None:
    """Run the read-only Reader migration cutover gate."""

    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run-report", required=True, type=Path)
    parser.add_argument("--apply-report", required=True, type=Path)
    parser.add_argument("--replay-report", required=True, type=Path)
    parser.add_argument("--target-report", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        result = evaluate(read_report(arguments.dry_run_report), read_report(arguments.apply_report),
                          read_report(arguments.replay_report), read_report(arguments.target_report))
    except (OSError, ValueError, json.JSONDecodeError):
        result = {"ready": False, "migrationKey": None, "migratedCount": -1,
                  "sourceHighWater": None, "errors": ["EVIDENCE_FILE_INVALID"]}
    print(json.dumps(result, separators=(",", ":")))
    if not result["ready"]:
        sys.exit(2)


if __name__ == "__main__":
    main()
