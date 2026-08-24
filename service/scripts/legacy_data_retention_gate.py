#!/usr/bin/env python3
"""Validate that the legacy MyTools database has a recoverable full backup."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys


REQUIRED_TABLES = {
    "drive_account",
    "drive_item_index",
    "ebook_metadata",
    "file_maintenance_log",
    "file_tag",
    "local_directory",
    "local_file",
    "media_package",
    "media_package_asset",
    "media_tag_artifact",
    "refresh_log",
    "t_api_log",
    "t_app_file",
    "t_app_market",
    "t_app_version",
    "t_book_source_search_cache",
    "t_dsh_session_binding",
    "t_email_verification_code",
    "t_feedback",
    "t_reader_marker",
    "t_reading_progress",
    "t_role",
    "t_shelf_book",
    "t_synced_book_source",
    "t_token",
    "t_user",
    "t_user_role",
    "webdav_account",
}


def evaluate(manifest: dict[str, object]) -> dict[str, object]:
    """检查备份清单是否覆盖全部已知旧表且已经验证可读取。"""
    errors: list[str] = []
    backup_file = manifest.get("backupFile")
    if not isinstance(backup_file, str) or not backup_file.strip():
        errors.append("backupFile is required")

    sha256 = manifest.get("sha256")
    if not isinstance(sha256, str) or re.fullmatch(r"[0-9a-f]{64}", sha256) is None:
        errors.append("sha256 must be a lowercase SHA-256 digest")

    if manifest.get("readVerified") is not True:
        errors.append("readVerified must be true")
    if manifest.get("inventoryComplete") is not True:
        errors.append("inventoryComplete must be true")

    unclassified = manifest.get("unclassifiedTables")
    if unclassified != []:
        errors.append("unclassifiedTables must be an empty list")

    raw_tables = manifest.get("tables")
    tables = raw_tables if isinstance(raw_tables, dict) else {}
    if not isinstance(raw_tables, dict):
        errors.append("tables must be an object of table row counts")
    missing = sorted(REQUIRED_TABLES - set(tables))
    if missing:
        errors.append("missing required tables: " + ", ".join(missing))

    invalid_counts = sorted(
        name for name, count in tables.items()
        if not isinstance(name, str) or isinstance(count, bool) or not isinstance(count, int) or count < 0
    )
    if invalid_counts:
        errors.append("invalid row counts: " + ", ".join(invalid_counts))

    return {
        "ready": not errors,
        "knownTableCount": len(REQUIRED_TABLES),
        "backedUpTableCount": len(tables),
        "totalRows": sum(count for count in tables.values()
                         if isinstance(count, int) and not isinstance(count, bool) and count >= 0),
        "errors": errors,
    }


def main() -> int:
    """执行只读的旧库数据保全门禁。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    arguments = parser.parse_args()
    manifest = json.loads(arguments.manifest.read_text(encoding="utf-8"))
    report = evaluate(manifest)
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    sys.exit(main())
