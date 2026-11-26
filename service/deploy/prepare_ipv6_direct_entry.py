"""使用现有私有 Cloudflare 凭据准备仅 DNS 解析的 IPv6 入口。"""

from __future__ import annotations

import argparse
import base64
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request


def credential(source: Path) -> tuple[str, str]:
    """读取现有隧道证书，并避免在日志中输出凭据。"""
    for kind, body in re.findall(r"-----BEGIN ([^-]+)-----\s*(.*?)\s*-----END \1-----",
                                source.read_text(), re.S):
        if "TUNNEL" in kind:
            value = json.loads(base64.b64decode("".join(body.split())))
            return value["apiToken"], value["zoneID"]
    raise ValueError("Existing Cloudflare certificate has no DNS credential")


def stable_address() -> str:
    """优先选择物理接口上的稳定全球单播 IPv6 地址。"""
    result = subprocess.run(["ip", "-j", "-6", "addr", "show", "scope", "global"],
                            capture_output=True, text=True, check=True)
    candidates: list[tuple[int, str]] = []
    for interface in json.loads(result.stdout):
        if interface["ifname"].startswith(("docker", "br-", "veth", "tun", "wg")):
            continue
        for entry in interface.get("addr_info", []):
            if "local" not in entry:
                continue
            address = ipaddress.ip_address(entry["local"])
            if not isinstance(address, ipaddress.IPv6Address) or not address.is_global:
                continue
            if entry.get("temporary") or entry.get("deprecated") or entry.get("preferred_life_time") == 0:
                continue
            candidates.append((0 if interface["ifname"].startswith("en") else 1, str(address)))
    if not candidates:
        raise ValueError("No stable global IPv6 address available")
    return sorted(candidates)[0][1]


def api(token: str, path: str, method: str = "GET", data: dict | None = None) -> object:
    """调用 Cloudflare API，不在日志中记录请求和响应正文。"""
    request = urllib.request.Request("https://api.cloudflare.com/client/v4" + path,
        data=None if data is None else json.dumps(data).encode(), method=method,
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=20) as response:
        value = json.load(response)
    if not value.get("success"):
        raise ValueError("Cloudflare DNS operation was rejected")
    return value["result"]


def update_dns(domain: str, source: Path) -> bool:
    """仅创建或刷新明确指定的 AAAA 记录。"""
    token, zone = credential(source)
    zone_info = api(token, "/zones/" + zone)
    if not domain.endswith("." + zone_info["name"]):
        raise ValueError("Direct hostname is outside the configured zone")
    records = api(token, "/zones/" + zone + "/dns_records?name=" + domain)
    if any(record["type"] in ("A", "CNAME") for record in records):
        raise ValueError("Direct hostname already has a conflicting record")
    existing = [record for record in records if record["type"] == "AAAA"]
    if len(existing) > 1:
        raise ValueError("Direct hostname has ambiguous AAAA records")
    address = stable_address()
    if existing and existing[0]["content"] == address and not existing[0]["proxied"]:
        return False
    payload = {"type": "AAAA", "name": domain, "content": address, "ttl": 60, "proxied": False}
    if existing:
        api(token, "/zones/" + zone + "/dns_records/" + existing[0]["id"], "PATCH", payload)
    else:
        api(token, "/zones/" + zone + "/dns_records", "POST", payload)
    return True


def main() -> None:
    """准备 DNS 和私有 ACME 配置，或执行定期 DDNS 刷新。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", required=True)
    parser.add_argument("--cloudflared-cert", type=Path, required=True)
    parser.add_argument("--refresh-dns", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"(?:[a-z0-9-]+\.)+[a-z]{2,63}", args.domain):
        raise ValueError("Invalid direct hostname")
    if os.geteuid() != 0:
        raise ValueError("Root is required for private certificate files")
    changed = update_dns(args.domain, args.cloudflared_cert)
    if not args.refresh_dns:
        directory = Path("/etc/mytools/direct-ipv6")
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        token, _ = credential(args.cloudflared_cert)
        target = directory / "cloudflare.ini"
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write("dns_cloudflare_api_token = " + token + "\n")
        target.chmod(0o600)
    print(json.dumps({"dnsReady": True, "dnsChanged": changed, "proxied": False}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"dnsReady": False, "errorType": type(error).__name__}))
        raise SystemExit(1) from None
