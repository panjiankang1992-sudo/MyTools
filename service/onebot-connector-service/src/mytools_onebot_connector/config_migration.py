"""从旧 DownloadBot 配置安全生成 OneBot 账户迁移清单。"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlparse
from urllib.request import Request, urlopen

import yaml

from .service import validated_account


def build_manifest(config_path: Path) -> dict:
    """读取旧配置并生成强制禁用且不包含凭据值的确定性清单。"""
    document = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError("legacy config root must be an object")
    raw_accounts = document.get("onebot", [])
    if not isinstance(raw_accounts, list):
        raise ValueError("legacy onebot config must be a list")
    accounts = []
    rejected = []
    seen = set()
    for index, raw in enumerate(raw_accounts):
        try:
            if not isinstance(raw, dict):
                raise ValueError("account must be an object")
            payload = {
                "externalKey": str(raw["id"]),
                "httpBaseUrl": str(raw.get("http_base_url", "http://127.0.0.1:3000")),
                "secretRef": f"env://{raw['token_env']}",
                "hostQqRoot": str(raw.get("host_qq_root", "/opt/napcat/qq")),
                "containerQqRoot": str(raw.get("container_qq_root", "/app/.config/QQ")),
                "enabled": False,
            }
            validated_account(payload)
            if payload["externalKey"] in seen:
                raise ValueError("duplicate external key")
            seen.add(payload["externalKey"])
            accounts.append(payload)
        except (KeyError, TypeError, ValueError) as exception:
            rejected.append({"index": index, "reason": str(exception)})
    accounts.sort(key=lambda item: item["externalKey"])
    return {"source": str(config_path), "dryRun": True, "accounts": accounts,
            "summary": {"accepted": len(accounts), "rejected": len(rejected)},
            "rejected": rejected}


def apply_manifest(manifest: dict, connector_url: str, admin_token: str) -> dict:
    """将无拒绝项的清单幂等登记到回环地址 Connector。"""
    parsed = urlparse(connector_url)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "::1", "localhost"} \
            or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("connector URL must be loopback HTTP")
    if manifest["summary"]["rejected"]:
        raise ValueError("manifest contains rejected accounts")
    if not admin_token:
        raise ValueError("connector admin token is missing")
    results = []
    for account in manifest["accounts"]:
        request = Request(connector_url.rstrip("/") + "/internal/v1/accounts",
                          data=json.dumps(account, separators=(",", ":")).encode(), method="POST",
                          headers={"Authorization": f"Bearer {admin_token}",
                                   "Content-Type": "application/json"})
        with urlopen(request, timeout=10) as response:
            if response.status not in {200, 201}:
                raise RuntimeError("connector account registration failed")
            result = json.loads(response.read(64 * 1024).decode("utf-8"))
        results.append({"externalKey": account["externalKey"], "id": result["id"],
                        "enabled": result["enabled"]})
    return {"dryRun": False, "applied": results, "summary": {"applied": len(results)}}


def main() -> None:
    """执行默认 dry-run 的旧 OneBot 账户配置迁移。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--connector-url", default="http://127.0.0.1:23255")
    parser.add_argument("--admin-token-env", default="ONEBOT_CONNECTOR_ADMIN_TOKEN")
    arguments = parser.parse_args()
    manifest = build_manifest(arguments.config)
    result = apply_manifest(manifest, arguments.connector_url,
                            os.environ.get(arguments.admin_token_env, "")) \
        if arguments.apply else manifest
    json.dump(result, sys.stdout, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
