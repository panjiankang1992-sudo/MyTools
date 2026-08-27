#!/usr/bin/env python3
"""Plan or apply the legacy resource move into one username namespace."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil

USERNAME = re.compile(r"^[A-Za-z0-9_]{1,64}$")
DIRECTORIES = ("ebook", "media", "big_media")


def inventory(path: Path) -> tuple[int, int]:
    """Return regular file count and bytes without following symbolic links."""
    files = 0
    size = 0
    if not path.exists():
        return files, size
    for root, _, names in os.walk(path, followlinks=False):
        for name in names:
            candidate = Path(root) / name
            if candidate.is_file() and not candidate.is_symlink():
                try:
                    size += candidate.stat().st_size
                    files += 1
                except FileNotFoundError:
                    # 盘点期间派生文件可能被原子替换，忽略已消失的目录项并继续核验稳定文件。
                    continue
    return files, size


def migrate(root: Path, username: str, apply: bool) -> dict:
    """Move exact legacy directories and verify inventories before and after."""
    if root != root.resolve() or not root.is_absolute() or str(root) == "/":
        raise ValueError("resource root must be an absolute normalized non-root path")
    if not USERNAME.fullmatch(username):
        raise ValueError("username is invalid")
    user_root = root / username
    operations = []
    for name in DIRECTORIES:
        source = root / name
        target = user_root / name
        before = inventory(source)
        if source.exists() and target.exists():
            raise ValueError(f"both source and target exist: {name}")
        current = before if source.exists() else inventory(target)
        operations.append({"name": name, "source": str(source), "target": str(target),
                           "files": current[0], "bytes": current[1], "present": source.exists(),
                           "alreadyMigrated": target.exists() and not source.exists()})
    if apply:
        user_root.mkdir(mode=0o750, parents=True, exist_ok=True)
        for operation in operations:
            source = Path(operation["source"])
            target = Path(operation["target"])
            if source.exists():
                # 同一资源盘内使用原子目录重命名，避免复制数百 GB 数据。
                source.rename(target)
            after = inventory(target)
            if after != (operation["files"], operation["bytes"]):
                raise RuntimeError(f"inventory mismatch after moving {operation['name']}")
        (user_root / "music").mkdir(mode=0o750, exist_ok=True)
    return {"applied": apply, "resourceRoot": str(root), "username": username,
            "operations": operations,
            "legacyDatabaseSql": [
                f"UPDATE local_file SET file_path=CONCAT('{root}/{username}/',SUBSTRING(file_path,{len(str(root)) + 2})) WHERE file_path LIKE '{root}/%';",
                f"UPDATE local_file SET thumbnail_path=CONCAT('{root}/{username}/',SUBSTRING(thumbnail_path,{len(str(root)) + 2})) WHERE thumbnail_path LIKE '{root}/%';",
                f"UPDATE local_directory SET directory_path=CONCAT('{root}/{username}/',SUBSTRING(directory_path,{len(str(root)) + 2})) WHERE directory_path LIKE '{root}/%';",
            ]}


def main() -> None:
    """Parse arguments and emit a machine-readable migration report."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="/opt/extend/resource")
    parser.add_argument("--username", default="yuyutian")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--report")
    arguments = parser.parse_args()
    report = migrate(Path(arguments.root), arguments.username, arguments.apply)
    document = json.dumps(report, ensure_ascii=False, indent=2)
    if arguments.report:
        target = Path(arguments.report)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(target.suffix + ".tmp")
        temporary.write_text(document + "\n", encoding="utf-8")
        shutil.move(temporary, target)
    print(document)


if __name__ == "__main__":
    main()
