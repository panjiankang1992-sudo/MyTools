"""只读解析 DownloadBot 配置中的 PikPak 脱敏迁移元数据。"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import yaml

SAFE_KEY = re.compile(r"^[A-Za-z0-9_-]{1,128}$")

class LegacyPikPakConfigExporter:
    """从显式旧配置路径导出不含凭据和本机路径的稳定账户页。"""
    def __init__(self, config_path: str):
        path = Path(config_path)
        if not path.is_absolute():
            raise ValueError("legacy DownloadBot config path must be absolute")
        self._path = path

    def export_page(self, after_id: str | None, limit: int) -> dict:
        """返回按旧账户 ID 排序的一页安全配置。"""
        if limit < 1 or limit > 200:
            raise ValueError("limit is outside the supported range")
        items = self._load()
        selected = [item for item in items if after_id is None or item["externalKey"] > after_id][:limit]
        return {"items": selected, "nextAfterId": selected[-1]["externalKey"] if len(selected) == limit else None,
                "totalCount": len(items), "collectionSha256": collection_digest(items)}

    def _load(self) -> list[dict]:
        document = yaml.safe_load(self._path.read_text(encoding="utf-8"))
        if not isinstance(document, dict):
            raise ValueError("legacy DownloadBot config must be an object")
        links = document.get("link_download") or {}
        if not isinstance(links, dict):
            raise ValueError("legacy link_download config is invalid")
        offline_root = safe_path(str(links.get("pikpak_offline_dir") or "DownloadBot/offline"))
        result = []
        for raw in document.get("pikpak") or []:
            if not isinstance(raw, dict):
                raise ValueError("legacy PikPak account config is invalid")
            external_key = str(raw.get("id") or "").strip()
            remote_key = str(raw.get("remote_name") or "pikpak").strip()
            if not SAFE_KEY.fullmatch(external_key) or not SAFE_KEY.fullmatch(remote_key):
                raise ValueError("legacy PikPak account identity is invalid")
            result.append({"externalKey": external_key, "remoteKey": remote_key,
                "offlineRoot": offline_root, "readyRoot": safe_path(str(raw.get("watch_dir") or "")),
                "legacyEnabled": bool(raw.get("enabled", True)),
                "stableSeconds": safe_stable_seconds(raw.get("settle_seconds", 60))})
        result.sort(key=lambda item: item["externalKey"])
        if len({item["externalKey"] for item in result}) != len(result):
            raise ValueError("legacy PikPak account id is duplicated")
        return result

def safe_path(value: str) -> str:
    """规范化不含 remote key 的云端相对路径。"""
    path = value.strip().strip("/")
    if not path or len(path) > 512 or "\\" in path or "\x00" in path:
        raise ValueError("legacy PikPak cloud path is invalid")
    if any(part in {"", ".", ".."} for part in path.split("/")):
        raise ValueError("legacy PikPak cloud path is invalid")
    return path

def safe_stable_seconds(value: object) -> int:
    """校验旧稳定窗口可由新 Connector 完整表达。"""
    seconds = int(float(value))
    if seconds < 1 or seconds > 86400:
        raise ValueError("legacy PikPak stable window is invalid")
    return seconds

def collection_digest(items: list[dict]) -> str:
    """计算安全配置集合的确定性摘要。"""
    digest = hashlib.sha256()
    for item in items:
        payload = json.dumps(item, sort_keys=True, separators=(",", ":")).encode()
        digest.update(len(payload).to_bytes(4, "big")); digest.update(payload)
    return digest.hexdigest()
