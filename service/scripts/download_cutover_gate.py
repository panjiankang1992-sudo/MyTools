#!/usr/bin/env python3
"""Validate DownloadBot migration evidence without changing runtime state."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from uuid import UUID

MAXIMUM_REPORT_BYTES = 1024 * 1024
DIGEST = re.compile(r"^[a-f0-9]{64}$")
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


def read_report(path: Path) -> dict:
    """Read one bounded JSON evidence document."""
    if not path.is_file() or path.stat().st_size > MAXIMUM_REPORT_BYTES:
        raise ValueError("EVIDENCE_FILE_INVALID")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("EVIDENCE_FILE_INVALID")
    return value


def evaluate(snapshot: dict, dry_run: dict, applied: dict, replay: dict,
             reconciliations: list[dict], expected_reconciliations: int) -> dict:
    """Evaluate frozen-source, import, replay, and content reconciliation invariants."""
    errors: set[str] = set()
    snapshot_id = safe_text(snapshot.get("snapshotId"))
    if not valid_uuid(snapshot_id) or snapshot.get("status") != "SEALED":
        errors.add("SNAPSHOT_INVALID")
    snapshot_count = integer(snapshot, "itemCount", errors, "SNAPSHOT_REPORT_INVALID")
    rejection_count = integer(snapshot, "rejectionCount", errors, "SNAPSHOT_REPORT_INVALID")
    snapshot_digest = safe_text(snapshot.get("collectionSha256"))
    if rejection_count != 0:
        errors.add("SNAPSHOT_HAS_REJECTIONS")
    if DIGEST.fullmatch(snapshot_digest) is None:
        errors.add("SNAPSHOT_REPORT_INVALID")

    reports = (dry_run, applied, replay)
    migration_key = safe_text(dry_run.get("migrationKey"))
    if not MIGRATION_KEY.fullmatch(migration_key) \
            or any(safe_text(report.get("migrationKey")) != migration_key for report in reports):
        errors.add("MIGRATION_KEY_MISMATCH")
    if dry_run.get("dryRun") is not True or applied.get("dryRun") is not False \
            or replay.get("dryRun") is not False:
        errors.add("MIGRATION_MODE_INVALID")
    if any(safe_text(report.get("sourceSnapshotId")) != snapshot_id for report in reports):
        errors.add("SOURCE_SNAPSHOT_MISMATCH")
    if any(safe_text(report.get("digestSha256")) != snapshot_digest for report in reports):
        errors.add("SOURCE_DIGEST_MISMATCH")

    counts = [migration_counts(report, errors) for report in reports]
    dry_counts, apply_counts, replay_counts = counts
    if any(value[0] != snapshot_count for value in counts):
        errors.add("MIGRATION_COUNT_MISMATCH")
    if dry_counts != apply_counts:
        errors.add("DRY_RUN_APPLY_MISMATCH")
    if any(value[3] != 0 for value in counts):
        errors.add("MIGRATION_HAS_REJECTIONS")
    if any(value[1] + value[2] + value[3] != value[0] for value in counts):
        errors.add("MIGRATION_COUNT_INVALID")
    if replay_counts[1] != 0 or replay_counts[2] != snapshot_count:
        errors.add("REPLAY_NOT_IDEMPOTENT")

    if expected_reconciliations < 0 or len(reconciliations) != expected_reconciliations:
        errors.add("RECONCILIATION_COUNT_MISMATCH")
    identities: set[tuple[str, str]] = set()
    for report in reconciliations:
        event_id = safe_text(report.get("eventId"))
        request_id = safe_text(report.get("downloadRequestId"))
        identity = (event_id, request_id)
        if safe_text(report.get("sourceSnapshotId")) != snapshot_id:
            errors.add("RECONCILIATION_SNAPSHOT_MISMATCH")
        if not event_id or len(event_id) > 255 or not valid_uuid(request_id) or identity in identities:
            errors.add("RECONCILIATION_IDENTITY_INVALID")
        identities.add(identity)
        if report.get("matched") is not True or report.get("mismatchReasons") != []:
            errors.add("RECONCILIATION_FAILED")
        if not valid_reconciliation(report):
            errors.add("RECONCILIATION_REPORT_INVALID")
    return {"ready": not errors, "snapshotId": snapshot_id or None,
            "migrationKey": migration_key or None, "migratedCount": apply_counts[0],
            "reconciliationCount": len(reconciliations), "errors": sorted(errors)}


def migration_counts(report: dict, errors: set[str]) -> tuple[int, int, int, int]:
    """Read exported, accepted, skipped, and rejected counts."""
    return tuple(integer(report, name, errors, "MIGRATION_REPORT_INVALID")
                 for name in ("exported", "accepted", "skipped", "rejected"))


def integer(report: dict, name: str, errors: set[str], error: str) -> int:
    """Read one non-negative integer without accepting booleans."""
    value = report.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        errors.add(error)
        return -1
    return value


def valid_reconciliation(report: dict) -> bool:
    """Validate strict legacy/current content evidence without exposing it."""
    legacy = report.get("legacy")
    current = report.get("current")
    if not isinstance(legacy, dict) or not isinstance(current, dict):
        return False
    legacy_count = legacy.get("itemCount")
    current_count = current.get("itemCount")
    legacy_bytes = legacy.get("totalBytes")
    current_bytes = current.get("totalBytes")
    return legacy.get("legacyStatus") == "COMPLETED" and current.get("status") == "SUCCEEDED" \
        and all(isinstance(value, int) and not isinstance(value, bool) and value >= 0
                for value in (legacy_count, current_count, legacy_bytes, current_bytes)) \
        and legacy_count == current_count and legacy_bytes == current_bytes \
        and DIGEST.fullmatch(safe_text(legacy.get("contentSetSha256"))) is not None \
        and legacy.get("contentSetSha256") == current.get("contentSetSha256")


def safe_text(value: object) -> str:
    """Return one bounded string without serializing nested evidence."""
    return value if isinstance(value, str) and len(value) <= 255 else ""


def valid_uuid(value: str) -> bool:
    """Require a canonical UUID evidence identifier."""
    try:
        return str(UUID(value)) == value.lower()
    except (ValueError, AttributeError):
        return False


def main() -> None:
    """Run the read-only Download cutover evidence gate."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot-report", required=True, type=Path)
    parser.add_argument("--dry-run-report", required=True, type=Path)
    parser.add_argument("--apply-report", required=True, type=Path)
    parser.add_argument("--replay-report", required=True, type=Path)
    parser.add_argument("--reconciliation-report", action="append", default=[], type=Path)
    parser.add_argument("--expected-reconciliations", required=True, type=int)
    arguments = parser.parse_args()
    try:
        result = evaluate(read_report(arguments.snapshot_report),
                          read_report(arguments.dry_run_report),
                          read_report(arguments.apply_report),
                          read_report(arguments.replay_report),
                          [read_report(path) for path in arguments.reconciliation_report],
                          arguments.expected_reconciliations)
    except (OSError, ValueError, json.JSONDecodeError):
        result = {"ready": False, "snapshotId": None, "migrationKey": None,
                  "migratedCount": -1, "reconciliationCount": 0,
                  "errors": ["EVIDENCE_FILE_INVALID"]}
    print(json.dumps(result, separators=(",", ":")))
    if not result["ready"]:
        sys.exit(2)


if __name__ == "__main__":
    main()
