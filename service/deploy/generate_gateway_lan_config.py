"""Generate private-interface Gateway proxy and discovery files without installing them."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
from pathlib import Path


LAN_PORT = 23201
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
TEMPLATE_ROOT = Path(__file__).with_name("lan")


def normalize_lan_address(value: str) -> str:
    """Validate an exact RFC1918 IPv4 interface address."""
    address = ipaddress.ip_address(value)
    if not isinstance(address, ipaddress.IPv4Address) or not any(
            address in network for network in PRIVATE_NETWORKS):
        raise ValueError("LAN listener requires an RFC1918 IPv4 interface address")
    return str(address)


def nginx_statements(source: str) -> list[str]:
    """Read complete directives while preserving quoted HTML filters verbatim."""
    statements: list[str] = []
    current: list[str] = []
    quote = ""
    escaped = False
    comment = False
    for character in source:
        if comment:
            if character == "\n":
                comment = False
                current.append("\n")
            continue
        if escaped:
            current.append(character)
            escaped = False
            continue
        if character == "\\":
            current.append(character)
            escaped = True
            continue
        if quote:
            current.append(character)
            if character == quote:
                quote = ""
            continue
        if character in ("'", '"'):
            current.append(character)
            quote = character
        elif character == "#":
            comment = True
        elif character in "{}":
            current = []
        elif character == ";":
            current.append(character)
            statements.append("".join(current).strip())
            current = []
        else:
            current.append(character)
    if quote or escaped:
        raise ValueError("Existing DSH proxy configuration has an incomplete directive")
    return statements


def dsh_proxy_material(source_path: Path, key_include: Path | None = None) -> tuple[str, list[str]]:
    """Reuse the existing proxy key and HTML bridge without printing either."""
    if source_path.stat().st_size > 1024 * 1024:
        raise ValueError("Existing DSH proxy configuration is too large")
    statements = nginx_statements(source_path.read_text(encoding="utf-8"))
    values: set[str] = set()
    includes: set[str] = set()
    html_filters: list[str] = []
    for statement in statements:
        if re.match(r"^proxy_set_header\s+X-Dsh-Proxy-Key\b", statement):
            match = re.fullmatch(
                r"""proxy_set_header\s+X-Dsh-Proxy-Key\s+(?:"([^"]+)"|'([^']+)'|([^\s;]+))\s*;""",
                statement)
            if match is None:
                raise ValueError("Existing DSH proxy key directive is unsupported")
            value = next(group for group in match.groups() if group is not None)
            # 仅接受可安全放入引号的静态密钥，不展开变量或执行配置内容。
            if not re.fullmatch(r"[A-Za-z0-9_+/.=:~-]{32,512}", value):
                raise ValueError("Existing DSH proxy key must be a static safe token")
            values.add(value)
        elif re.match(r"^include\s", statement):
            match = re.fullmatch(r'include\s+"?(/[A-Za-z0-9/_.-]+)"?\s*;', statement)
            if match is not None and Path(match.group(1)).name == "mytools-dsh-auth-secret.conf":
                includes.add(match.group(1))
        elif re.match(r"^sub_filter(?:_once|_types)?\s", statement):
            html_filters.append(statement)
    if key_include is not None:
        includes = {str(key_include)}
    if includes:
        if len(includes) != 1 or values:
            raise ValueError("Existing DSH proxy configuration has ambiguous key sources")
        include = next(iter(includes))
        if not re.fullmatch(r"/[A-Za-z0-9/_.-]+", include) or ".." in Path(include).parts:
            raise ValueError("DSH proxy key include requires a safe absolute path")
        # 复用本机受限片段，不读取、复制或输出真实代理密钥。
        return 'include "' + include + '";', html_filters
    if len(values) != 1:
        raise ValueError("Existing DSH proxy configuration must contain one consistent proxy key")
    return 'proxy_set_header X-Dsh-Proxy-Key "' + next(iter(values)) + '";', html_filters


def private_write(path: Path, content: str) -> None:
    """Create a private new file without following or replacing an existing path."""
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(content)


def generate(lan_address: str | list[str], output: Path, dsh_proxy_config: Path,
             key_include: Path | None = None) -> dict[str, object]:
    """Create reviewable private files without changing running services."""
    supplied_addresses = [lan_address] if isinstance(lan_address, str) else lan_address
    addresses = list(dict.fromkeys(normalize_lan_address(value) for value in supplied_addresses))
    if not addresses:
        raise ValueError("At least one private interface address is required")
    proxy_directive, html_filters = dsh_proxy_material(dsh_proxy_config, key_include)
    nginx = (TEMPLATE_ROOT / "mytools-gateway-lan.conf.template").read_text(encoding="utf-8")
    api_parameters = (TEMPLATE_ROOT / "api-proxy-parameters.conf.template").read_text(encoding="utf-8").rstrip()
    nginx = nginx.replace("@API_PROXY_PARAMETERS@", api_parameters)
    listeners = "\n".join("    listen " + value + ":23201;" for value in addresses)
    nginx = nginx.replace("@LAN_LISTENERS@", listeners).replace("@DSH_PROXY_KEY_DIRECTIVE@", proxy_directive)
    nginx = nginx.replace("@DSH_HTML_FILTERS@", "\n".join("        " + value for value in html_filters))
    nginx = nginx.replace("@DSH_BROWSER_ORIGIN@", f"http://$server_addr:{LAN_PORT}")
    avahi = (TEMPLATE_ROOT / "mytools-gateway.service").read_text(encoding="utf-8")
    # 只创建新目录，避免覆盖已有配置；生成结果包含密钥，目录和文件均保持私有权限。
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    private_write(output / "mytools-gateway-lan.conf", nginx)
    private_write(output / "mytools-gateway.service", avahi)
    report: dict[str, object] = {
        "lanAddress": addresses[0],
        "lanAddresses": addresses,
        "lanPort": LAN_PORT,
        "serviceType": "_mytools-gateway._tcp.local",
        "upstream": "http://127.0.0.1:23200",
        "dshUpstream": "http://127.0.0.1:3080",
        "reusedDshHtmlFilters": len(html_filters),
        "installed": False,
        "legacyPortUnchanged": 23110,
    }
    private_write(output / "manifest.json", json.dumps(report, indent=2) + "\n")
    return report


def main() -> None:
    """Parse the explicit private interface, existing proxy and new output directory."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lan-address", required=True, action="append")
    parser.add_argument("--dsh-proxy-config", required=True, type=Path)
    parser.add_argument("--dsh-proxy-key-include", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        report = generate(arguments.lan_address, arguments.output, arguments.dsh_proxy_config,
                          arguments.dsh_proxy_key_include)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(json.dumps(report))


if __name__ == "__main__":
    main()
