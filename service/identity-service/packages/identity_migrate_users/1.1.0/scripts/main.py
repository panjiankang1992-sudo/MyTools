#!/usr/bin/env python3
"""迁移冻结的旧用户集合，不复制可重用的会话令牌。"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen

PAGE_SIZE = 100
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
DIGEST = re.compile(r"^[a-f0-9]{64}$")
BCRYPT = re.compile(r"^\$2[aby]\$\d{2}\$.{53}$")


class Client:
    """读取旧导出接口并调用隔离的 Identity 迁移接口。"""

    def __init__(self, mytools_url: str, mytools_token: str, identity_url: str,
                 identity_token: str, opener=urlopen):
        if not mytools_token or not identity_token:
            raise ValueError("Identity migration tokens are missing")
        self.mytools_url = mytools_url.rstrip("/")
        self.mytools_token = mytools_token
        self.identity_url = identity_url.rstrip("/")
        self.identity_token = identity_token
        self.opener = opener

    def page(self, after_id: int, high_water: int | None) -> dict:
        """在可选冻结高水位内读取一页。"""
        query = {"afterId": after_id, "limit": PAGE_SIZE}
        if high_water is not None:
            query["snapshotHighWater"] = high_water
        return self._request(self.mytools_url + "/internal/v1/migration/identity-users?"
                             + urlencode(query), "GET", None, self.mytools_token)

    def import_batch(self, migration_key: str, dry_run: bool, users: list[dict]) -> dict:
        """校验或导入一个有界用户批次。"""
        return self._request(self.identity_url + "/internal/v1/migrations/legacy-users",
                             "POST", {"migrationKey": migration_key, "dryRun": dry_run,
                                      "users": users}, self.identity_token)

    def _request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            body = response.read()
        if len(body) > 4 * 1024 * 1024:
            raise RuntimeError("Identity migration response is too large")
        value = json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise RuntimeError("Identity migration endpoint returned invalid data")
        return value


def execute(client: Client, migration_key: str, dry_run: bool,
            snapshot_high_water: int | None = None) -> dict:
    """读取并校验冻结来源集合，然后按有界批次导入。"""
    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Identity migration key is invalid")
    if snapshot_high_water is not None and (not isinstance(snapshot_high_water, int)
                                            or isinstance(snapshot_high_water, bool)
                                            or snapshot_high_water < 0):
        raise ValueError("Identity migration high water is invalid")
    users: list[dict] = []
    after_id = 0
    high_water = snapshot_high_water
    while True:
        page = client.page(after_id, high_water)
        values = page.get("users")
        current_high_water = page.get("snapshotHighWater")
        if not isinstance(values, list) or len(values) > PAGE_SIZE \
                or not isinstance(current_high_water, int) \
                or isinstance(current_high_water, bool) or current_high_water < 0:
            raise RuntimeError("Identity migration page is invalid")
        high_water = current_high_water if high_water is None else high_water
        if current_high_water != high_water:
            raise RuntimeError("Identity migration high water changed")
        for user in values:
            validate_user(user)
            users.append(user)
        next_after = page.get("nextAfterId")
        if not isinstance(next_after, int) or isinstance(next_after, bool) \
                or next_after < after_id or (values and next_after <= after_id):
            raise RuntimeError("Identity migration cursor did not advance")
        after_id = next_after
        if page.get("complete") is True:
            break
    if len({user["id"] for user in users}) != len(users):
        raise RuntimeError("Identity migration contains duplicate users")
    users.sort(key=lambda user: user["id"])
    source_digest = collection_digest(users)
    totals = {"accepted": 0, "skipped": 0, "rejected": 0}
    for offset in range(0, len(users), PAGE_SIZE):
        batch = users[offset:offset + PAGE_SIZE]
        result = client.import_batch(migration_key, dry_run, batch)
        if result.get("migrationKey") != migration_key or result.get("dryRun") is not dry_run \
                or result.get("exported") != len(batch) \
                or not DIGEST.fullmatch(str(result.get("digestSha256") or "")):
            raise RuntimeError("Identity migration batch evidence is invalid")
        counts = [read_count(result, name) for name in totals]
        if sum(counts) != len(batch):
            raise RuntimeError("Identity migration batch counts do not close")
        for name, value in zip(totals, counts):
            totals[name] += value
    return {"migrationKey": migration_key, "dryRun": dry_run, "exported": len(users),
            **totals, "digestSha256": source_digest, "sourceItemCount": len(users),
            "sourceDigestSha256": source_digest, "sourceHighWater": high_water or 0}


def validate_user(user: object) -> None:
    """校验稳定 Identity 迁移协议要求的字段。"""
    if not isinstance(user, dict) or not isinstance(user.get("id"), int) \
            or isinstance(user.get("id"), bool) or user["id"] <= 0 \
            or not BCRYPT.fullmatch(str(user.get("passwordHash") or "")) \
            or not isinstance(user.get("roles"), list):
        raise RuntimeError("Identity migration user is invalid")


def collection_digest(users: list[dict]) -> str:
    """按用户标识顺序计算与目标端兼容的集合摘要。"""
    collection = hashlib.sha256()
    for user in sorted(users, key=lambda value: value["id"]):
        payload = hashlib.sha256()
        update_digest(payload, user["id"], user.get("externalUserId"), user.get("username"),
                      user.get("email"), user.get("passwordHash"), user.get("status"),
                      user.get("credentialVersion"))
        for role in sorted(user["roles"]):
            update_digest(payload, role)
        update_digest(collection, user["id"], payload.hexdigest())
    return collection.hexdigest()


def update_digest(digest, *values: object) -> None:
    """使用共享长度前缀协议更新 SHA-256。"""
    for value in values:
        encoded = ("" if value is None else str(value)).encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)


def read_count(result: dict, name: str) -> int:
    """读取非负迁移计数且不接受布尔值。"""
    value = result.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise RuntimeError("Identity migration count is invalid")
    return value


def write_result(result: dict) -> None:
    """原子写入不含敏感信息的任务报告。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """运行一次显式参数化的用户迁移任务。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("MYTOOLS_INTERNAL_URL", "http://127.0.0.1:23110"),
                    os.getenv("IDENTITY_MIGRATION_INTERNAL_TOKEN", ""),
                    os.getenv("IDENTITY_SERVICE_URL", "http://127.0.0.1:23290"),
                    os.getenv("IDENTITY_INTERNAL_TOKEN", ""))
    write_result(execute(client, str(parameters["migrationKey"]), bool(parameters["dryRun"]),
                         parameters.get("snapshotHighWater")))


if __name__ == "__main__":
    main()
