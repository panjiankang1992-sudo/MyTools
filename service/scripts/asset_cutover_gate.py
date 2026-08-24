#!/usr/bin/env python3
"""Validate sealed legacy Asset migration evidence without changing runtime state."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys


MAXIMUM_REPORT_BYTES = 1024 * 1024
DIGEST = re.compile(r"^[a-f0-9]{64}$")
IDENTIFIER = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


def read_report(path: Path) -> dict:
    """Read one bounded local evidence document."""

    if not path.is_file() or path.stat().st_size > MAXIMUM_REPORT_BYTES:
        raise ValueError("EVIDENCE_FILE_INVALID")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("EVIDENCE_FILE_INVALID")
    return value


def evaluate(snapshot: dict, dry_run: dict, applied: dict, replay: dict,
             target: dict, expected_source_rejections: int) -> dict:
    """Evaluate snapshot closure, idempotency, and committed target equality."""

    errors: set[str] = set()
    reports = (dry_run, applied, replay)
    migration_key = safe_text(dry_run.get("migrationKey"))
    snapshot_id = safe_text(snapshot.get("snapshotId"))
    if not IDENTIFIER.fullmatch(migration_key) \
            or any(safe_text(report.get("migrationKey")) != migration_key for report in reports) \
            or safe_text(target.get("migrationKey")) != migration_key:
        errors.add("MIGRATION_KEY_MISMATCH")
    if not IDENTIFIER.fullmatch(snapshot_id) \
            or any(safe_text(report.get("sourceSnapshotId")) != snapshot_id for report in reports) \
            or safe_text(target.get("sourceSnapshotId")) != snapshot_id:
        errors.add("SOURCE_SNAPSHOT_MISMATCH")
    if dry_run.get("dryRun") is not True or applied.get("dryRun") is not False \
            or replay.get("dryRun") is not False:
        errors.add("MIGRATION_MODE_INVALID")
    captured = integer(snapshot, "captured", errors)
    rejected_source = integer(snapshot, "rejected", errors)
    high_water = integer(snapshot, "highWaterId", errors)
    if not isinstance(snapshot.get("ownerId"), int) or isinstance(snapshot.get("ownerId"), bool) \
            or snapshot["ownerId"] <= 0 or DIGEST.fullmatch(safe_text(
                snapshot.get("digestSha256"))) is None:
        errors.add("SOURCE_SNAPSHOT_INVALID")
    if expected_source_rejections < 0 or rejected_source != expected_source_rejections:
        errors.add("SOURCE_REJECTION_COUNT_MISMATCH")
    migration_digest = safe_text(dry_run.get("digestSha256"))
    if DIGEST.fullmatch(migration_digest) is None:
        errors.add("MIGRATION_DIGEST_INVALID")
    for report in reports:
        if integer(report, "exported", errors) != captured \
                or safe_text(report.get("digestSha256")) != migration_digest:
            errors.add("MIGRATION_SOURCE_MISMATCH")
    counts = [migration_counts(report, errors) for report in reports]
    if counts[0] != counts[1]:
        errors.add("DRY_RUN_APPLY_MISMATCH")
    if any(sum(values[1:]) != values[0] for values in counts):
        errors.add("MIGRATION_COUNT_MISMATCH")
    if any(values[3] != 0 for values in counts):
        errors.add("MIGRATION_HAS_REJECTIONS")
    if counts[2][1] != 0 or counts[2][2] != captured:
        errors.add("REPLAY_NOT_IDEMPOTENT")
    target_count = integer(target, "itemCount", errors)
    target_digest = safe_text(target.get("collectionSha256"))
    if target_count != captured:
        errors.add("TARGET_COUNT_MISMATCH")
    if DIGEST.fullmatch(target_digest) is None or target_digest != migration_digest:
        errors.add("TARGET_DIGEST_MISMATCH")
    return {"ready": not errors, "migrationKey": migration_key or None,
            "sourceSnapshotId": snapshot_id or None, "sourceHighWater": high_water,
            "migratedCount": target_count, "sourceRejections": rejected_source,
            "errors": sorted(errors)}


def migration_counts(report: dict, errors: set[str]) -> tuple[int, int, int, int]:
    """Read exported, accepted, skipped, and rejected counts."""

    return tuple(integer(report, name, errors)
                 for name in ("exported", "accepted", "skipped", "rejected"))


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
    """Run the read-only Asset migration cutover gate."""

    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot-report", required=True, type=Path)
    parser.add_argument("--dry-run-report", required=True, type=Path)
    parser.add_argument("--apply-report", required=True, type=Path)
    parser.add_argument("--replay-report", required=True, type=Path)
    parser.add_argument("--target-report", required=True, type=Path)
    parser.add_argument("--expected-source-rejections", type=int, default=0)
    arguments = parser.parse_args()
    try:
        result = evaluate(read_report(arguments.snapshot_report),
                          read_report(arguments.dry_run_report), read_report(arguments.apply_report),
                          read_report(arguments.replay_report), read_report(arguments.target_report),
                          arguments.expected_source_rejections)
    except (OSError, ValueError, json.JSONDecodeError):
        result = {"ready": False, "migrationKey": None, "sourceSnapshotId": None,
                  "sourceHighWater": -1, "migratedCount": -1, "sourceRejections": -1,
                  "errors": ["EVIDENCE_FILE_INVALID"]}
    print(json.dumps(result, separators=(",", ":")))
    if not result["ready"]:
        sys.exit(2)


if __name__ == "__main__":
    main()
