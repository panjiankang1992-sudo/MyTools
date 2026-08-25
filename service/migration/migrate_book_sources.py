#!/usr/bin/env python3
"""将旧 MyTools 书源快照幂等导入 Reader Service。"""

import argparse
import base64
import json
import subprocess
import urllib.request
from pathlib import Path


def read_env(path: Path) -> dict[str, str]:
    """读取 systemd EnvironmentFile 格式的简单键值。"""
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def legacy_rows(env: dict[str, str], owner_id: int) -> list[tuple[str, str, str]]:
    """从旧库读取书源地址、同步键和 Base64 快照。"""
    query = (
        "SELECT sync_key,source_url,REPLACE(TO_BASE64(snapshot_json),'\\n','') "
        f"FROM t_synced_book_source WHERE user_id={owner_id} AND deleted=0 ORDER BY source_url"
    )
    command = ["mysql", "--batch", "--skip-column-names",
               "-h", env["MYTOOLS_LEGACY_DB_HOST"], "-P", env["MYTOOLS_LEGACY_DB_PORT"],
               "-u", env["MYTOOLS_LEGACY_DB_USER"], "-p" + env["MYTOOLS_LEGACY_DB_PASSWORD"],
               env["MYTOOLS_LEGACY_DB_NAME"], "-e", query]
    output = subprocess.run(command, check=True, capture_output=True, text=True).stdout
    return [tuple(line.split("\t", 2)) for line in output.splitlines() if line]


def migrate(env: dict[str, str], owner_id: int, reader_url: str) -> tuple[int, int]:
    """逐条调用 Reader 的幂等保存接口。"""
    saved = 0
    failed = 0
    for sync_key, source_url, encoded_snapshot in legacy_rows(env, owner_id):
        snapshot_json = base64.b64decode(encoded_snapshot).decode("utf-8")
        payload = json.dumps({"ownerId": owner_id, "syncKey": sync_key, "sourceUrl": source_url,
                              "snapshotJson": snapshot_json, "deleted": False}).encode("utf-8")
        request = urllib.request.Request(reader_url.rstrip("/") + "/api/v1/reader-state/sources",
                                         data=payload, method="PUT",
                                         headers={"Authorization": "Bearer " + env["READER_INTERNAL_TOKEN"],
                                                  "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                if response.status == 200:
                    saved += 1
                else:
                    failed += 1
        except Exception:
            failed += 1
    return saved, failed


def main() -> int:
    """执行书源迁移并输出不含业务内容的计数。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--owner-id", type=int, required=True)
    parser.add_argument("--reader-url", default="http://127.0.0.1:23230")
    arguments = parser.parse_args()
    saved, failed = migrate(read_env(arguments.env_file), arguments.owner_id, arguments.reader_url)
    print(json.dumps({"saved": saved, "failed": failed}, separators=(",", ":")))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
