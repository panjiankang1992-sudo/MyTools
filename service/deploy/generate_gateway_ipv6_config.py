"""生成 IPv6 TLS 入口并保留已审查的网关路由边界。"""

from __future__ import annotations

import argparse
from pathlib import Path
import re

from generate_gateway_lan_config import TEMPLATE_ROOT, dsh_proxy_material, private_write


def render(domain: str, port: int, dsh_config: Path) -> str:
    """复用 API 路由和 DSH 过滤规则，使用独立 TLS 与安全 Cookie。"""
    if not re.fullmatch(r"(?:[a-z0-9-]+\.)+[a-z]{2,63}", domain) or port not in (443, 8443):
        raise ValueError("Invalid direct listener")
    key, filters = dsh_proxy_material(dsh_config)
    template = (TEMPLATE_ROOT / "mytools-gateway-lan.conf.template").read_text()
    parameters = (TEMPLATE_ROOT / "api-proxy-parameters.conf.template").read_text().rstrip()
    # 公网 IPv6 的大 JSON 需要压缩；下载请求显式使用 identity，保持原始字节。
    parameters = parameters.replace("proxy_set_header X-Forwarded-Proto http;",
                                    "proxy_set_header X-Forwarded-Proto https;")
    parameters = parameters.replace("gzip off;", "gzip on;\n        gzip_types application/json;\n"
        "        gzip_min_length 1024;\n        gzip_comp_level 5;\n        gzip_vary on;")
    text = template.replace("@API_PROXY_PARAMETERS@", parameters)
    text = text.replace("@LAN_LISTENERS@", f"    listen [::]:{port} ssl ipv6only=on;")
    text = text.replace("    server_name _;", f"    server_name {domain};\n"
        f"    ssl_certificate /etc/letsencrypt/live/{domain}/fullchain.pem;\n"
        f"    ssl_certificate_key /etc/letsencrypt/live/{domain}/privkey.pem;\n"
        "    ssl_protocols TLSv1.2 TLSv1.3;")
    text = re.sub(r"^    (?:allow (?:10\.0\.0\.0/8|172\.16\.0\.0/12|192\.168\.0\.0/16)|deny all);\n", "", text, flags=re.M)
    text = text.replace("@DSH_PROXY_KEY_DIRECTIVE@", key)
    text = text.replace("@DSH_HTML_FILTERS@", "\n".join("        " + line for line in filters))
    text = text.replace("/internal/dsh-web/lan/", "/internal/dsh-web/")
    text = text.replace("mytools_gateway_lan", "mytools_gateway_ipv6")
    text = text.replace("mytools-gateway-lan-error", "mytools-gateway-ipv6-error")
    origin = "https://" + domain + (":" + str(port) if port != 443 else "")
    return text.replace("@DSH_BROWSER_ORIGIN@", origin)


def main() -> None:
    """生成私有配置文件，由部署流程负责安装和重载。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", required=True)
    parser.add_argument("--port", type=int, default=443)
    parser.add_argument("--dsh-proxy-config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    private_write(args.output, render(args.domain, args.port, args.dsh_proxy_config))


if __name__ == "__main__":
    main()
