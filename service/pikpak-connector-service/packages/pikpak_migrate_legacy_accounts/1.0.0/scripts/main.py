#!/usr/bin/env python3
"""迁移 DownloadBot PikPak 脱敏账户配置并保持新账户禁用。"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import UUID

SAFE_KEY = re.compile(r"^[A-Za-z0-9_-]{1,128}$")

class HttpClient:
    """旧适配器导出与新 Connector 写入客户端。"""
    def __init__(self, source_url: str, source_token: str, target_url: str, target_token: str):
        if not source_token or not target_token:
            raise ValueError("migration tokens are missing")
        self.source_url, self.source_token = source_url.rstrip("/"), source_token
        self.target_url, self.target_token = target_url.rstrip("/"), target_token
    def page(self, after_id: str | None) -> dict:
        """读取一页脱敏旧账户。"""
        query = {"limit": 100}
        if after_id: query["afterId"] = after_id
        return self._request("GET", self.source_url + "/internal/v1/migration/downloadbot/pikpak-accounts?" + urlencode(query), self.source_token)
    def register(self, payload: dict) -> dict:
        """幂等登记一个新账户。"""
        return self._request("POST", self.target_url + "/api/internal/v1/pikpak/accounts", self.target_token, payload)
    def _request(self, method: str, url: str, token: str, payload: dict | None = None) -> dict:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(url, data=body, method=method, headers={"Authorization": f"Bearer {token}",
            "Accept": "application/json", "Content-Type": "application/json"})
        with urlopen(request, timeout=30) as response: result = json.loads(response.read().decode())
        if not isinstance(result, dict): raise RuntimeError("migration endpoint returned invalid response")
        return result

def mappings(parameters: dict) -> dict[str, dict]:
    """校验显式 Provider 与 Secret 引用映射。"""
    result = {}
    raw_values = parameters.get("accountMappings")
    if not isinstance(raw_values, list) or len(raw_values) > 200:
        raise ValueError("accountMappings is invalid")
    for raw in raw_values:
        if not isinstance(raw, dict) or set(raw) != {"externalKey", "storageProviderId", "secretRef"}:
            raise ValueError("account mapping fields are invalid")
        key = str(raw["externalKey"])
        if not SAFE_KEY.fullmatch(key) or key in result:
            raise ValueError("account mapping identity is invalid")
        provider = str(UUID(str(raw["storageProviderId"])))
        secret_ref = str(raw["secretRef"])
        if not secret_ref.startswith("secret://") or len(secret_ref) > 512:
            raise ValueError("account Secret reference is invalid")
        result[key] = {"storageProviderId": provider, "secretRef": secret_ref}
    return result

def load_source(client: HttpClient) -> tuple[list[dict], str]:
    """分页读取并验证来源数量及集合摘要闭合。"""
    items, after_id, expected_count, expected_digest = [], None, None, None
    while True:
        page = client.page(after_id)
        if expected_count is None:
            expected_count, expected_digest = int(page["totalCount"]), str(page["collectionSha256"])
        elif expected_count != int(page["totalCount"]) or expected_digest != str(page["collectionSha256"]):
            raise RuntimeError("legacy PikPak config changed during export")
        values = page.get("items")
        if not isinstance(values, list): raise RuntimeError("legacy PikPak page is invalid")
        items.extend(values)
        after_id = page.get("nextAfterId")
        if not after_id: break
        if len(items) > 200: raise RuntimeError("legacy PikPak account limit exceeded")
    if len(items) != expected_count or collection_digest(items) != expected_digest:
        raise RuntimeError("legacy PikPak collection digest mismatch")
    return items, expected_digest

def execute(parameters: dict, client: HttpClient) -> dict:
    """执行 dry-run 或正式禁用账户迁移。"""
    account_mappings = mappings(parameters)
    items, digest = load_source(client)
    for item in items:
        stable_seconds = int(item["stableSeconds"])
        if stable_seconds < 1 or stable_seconds > 86400:
            raise ValueError("legacy PikPak stable window is invalid")
    source_keys = {str(item["externalKey"]) for item in items}
    if source_keys != set(account_mappings):
        raise ValueError("accountMappings must exactly cover legacy accounts")
    dry_run = bool(parameters["dryRun"])
    ids = []
    if not dry_run:
        for item in items:
            mapping = account_mappings[item["externalKey"]]
            result = client.register({"externalKey": item["externalKey"],
                "storageProviderId": mapping["storageProviderId"], "secretRef": mapping["secretRef"],
                "remoteKey": item["remoteKey"], "offlineRoot": item["offlineRoot"],
                "readyRoot": item["readyRoot"], "enabled": False,
                "stableSeconds": int(item["stableSeconds"])})
            ids.append(str(UUID(str(result["id"]))))
    return {"dryRun": dry_run, "sourceCount": len(items), "acceptedCount": len(ids),
        "legacyEnabledCount": sum(bool(item["legacyEnabled"]) for item in items),
        "collectionSha256": digest, "accountIds": ids}

def collection_digest(items: list[dict]) -> str:
    """复算适配器长度前缀集合摘要。"""
    digest = hashlib.sha256()
    for item in items:
        payload = json.dumps(item, sort_keys=True, separators=(",", ":")).encode()
        digest.update(len(payload).to_bytes(4, "big")); digest.update(payload)
    return digest.hexdigest()

def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"]); target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":")); temporary = Path(handle.name)
    temporary.replace(target)

def main() -> None:
    """执行账户迁移任务。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = HttpClient(os.getenv("DOWNLOADBOT_ADAPTER_URL", "http://127.0.0.1:23221"),
        os.environ["DOWNLOADBOT_PIKPAK_EXPORT_TOKEN"],
        os.getenv("PIKPAK_CONNECTOR_URL", "http://127.0.0.1:23285"),
        os.environ["PIKPAK_CONNECTOR_TOKEN"])
    write_result(execute(context["parameters"], client))
if __name__ == "__main__": main()
