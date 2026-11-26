#!/usr/bin/env python3
"""从执行节点探测出口并上报去标识地区，原始地址只在内存中使用。"""
from __future__ import annotations

import base64
from datetime import datetime, timezone
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import uuid

MAX_BYTES = 131072
IP_ENDPOINTS = {"IPV4": "https://api.ipify.org?format=json", "IPV6": "https://api6.ipify.org?format=json"}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """重定向不得携带节点或供应商凭据离开固定目标。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """显式拒绝所有跳转。"""
        raise ValueError("Redirect rejected")


class ExplicitProxy(urllib.request.ProxyHandler):
    """显式代理不受进程 NO_PROXY 设置影响，避免出口声明与实际连接不一致。"""

    def proxy_open(self, req, proxy, type_):
        """通过已校验 HTTP CONNECT 代理访问 HTTPS 目标。"""
        req.set_proxy(urllib.parse.urlsplit(proxy).netloc, "http")
        return None


def network_client(proxy: str = ""):
    """只使用显式配置的代理，避免继承桌面环境的出口设置。"""
    if proxy:
        parsed = urllib.parse.urlsplit(proxy)
        if parsed.scheme != "http" or not parsed.hostname or parsed.username or parsed.password \
                or parsed.query or parsed.fragment or parsed.path not in {"", "/"} or any(c.isspace() for c in proxy):
            raise ValueError("Invalid proxy configuration")
    return urllib.request.build_opener(ExplicitProxy({"https": proxy}) if proxy else urllib.request.ProxyHandler({}), NoRedirect())


def request_json(client, url: str, *, headers: dict | None = None, data: dict | None = None) -> dict:
    """使用响应大小和读取时间上限，不在异常中回显远程载荷。"""
    body = None if data is None else json.dumps(data, separators=(",", ":")).encode()
    request = urllib.request.Request(url, data=body, headers={"Accept": "application/json",
            "User-Agent": "MyTools-NewsRegion/1.0", "Content-Type": "application/json", **(headers or {})})
    deadline = time.monotonic() + 20
    with client.open(request, timeout=8) as response:
        if response.status != 200:
            raise ValueError("Unexpected response status")
        payload = bytearray()
        while True:
            chunk = response.read(min(8192, MAX_BYTES + 1 - len(payload)))
            if not chunk:
                break
            payload.extend(chunk)
            if len(payload) > MAX_BYTES or time.monotonic() > deadline:
                raise ValueError("Response budget exceeded")
    value = json.loads(payload)
    if not isinstance(value, dict):
        raise ValueError("Invalid JSON object")
    return value


def public_address(value: str, family: str):
    """拒绝非公网、组播、作用域地址和地址族自动漂移。"""
    if not isinstance(value, str) or "%" in value or family not in IP_ENDPOINTS:
        raise ValueError("Invalid egress address")
    address = ipaddress.ip_address(value)
    if not address.is_global or address.is_multicast or address.is_reserved \
            or getattr(address, "ipv4_mapped", None) is not None or address.version != (4 if family == "IPV4" else 6):
        raise ValueError("Invalid egress address")
    return address


def read_egress(client, family: str):
    """从固定官方端点取得实际请求出口。"""
    return public_address(request_json(client, IP_ENDPOINTS[family]).get("ip"), family)


def geo_fields(record: dict) -> dict:
    """只保留行政区与精度字段，不保留经纬度、邮编或供应商原始对象。"""
    country = record.get("country", {}).get("iso_code")
    if not isinstance(country, str) or re.fullmatch(r"[A-Z]{2}", country) is None:
        raise ValueError("Missing country evidence")
    subdivisions = record.get("subdivisions", [])
    subdivision = subdivisions[0].get("iso_code") if subdivisions else None
    if subdivision is not None and (not isinstance(subdivision, str) or re.fullmatch(r"[A-Z0-9]{1,3}", subdivision) is None):
        raise ValueError("Invalid subdivision evidence")
    city = record.get("city", {})
    city_id = city.get("geoname_id")
    radius = record.get("location", {}).get("accuracy_radius")
    if city_id is not None and (type(city_id) is not int or not 0 < city_id < 2**63):
        raise ValueError("Invalid city evidence")
    if radius is not None and (type(radius) is not int or not 0 <= radius <= 20000):
        raise ValueError("Invalid accuracy evidence")
    name = city.get("names", {}).get("en")
    if name is not None and not isinstance(name, str):
        raise ValueError("Invalid city name")
    return {"countryCode": country, "subdivisionCode": subdivision, "cityGeonameId": city_id,
            "cityName": name[:160] if name else None, "accuracyRadiusKm": radius}


def locate(address, config: dict, client) -> tuple[dict, str]:
    """按显式配置使用本地城市库或 MaxMind HTTPS 服务。"""
    if config["provider"] == "MAXMIND_MMDB":
        import maxminddb
        with maxminddb.open_database(config["mmdb"]) as reader:
            metadata = reader.metadata()
            # 过期或错误类型数据库不能伪装为最新城市证据。
            if metadata.database_type not in {"GeoLite2-City", "GeoIP2-City"} \
                    or not 0 <= time.time() - metadata.build_epoch <= 30 * 86400:
                raise ValueError("City database is unavailable or stale")
            return geo_fields(reader.get(str(address)) or {}), metadata.database_type + ":" + str(metadata.build_epoch)
    if config["provider"] != "MAXMIND_WEB" or config["web_host"] not in {"geolite.info", "geoip.maxmind.com"} \
            or not config["account"].isdigit() or not config["license"]:
        raise ValueError("Geolocation provider is not configured")
    credential = base64.b64encode((config["account"] + ":" + config["license"]).encode()).decode()
    record = request_json(client, "https://" + config["web_host"] + "/geoip/v2.1/city/" + str(address),
                          headers={"Authorization": "Basic " + credential})
    # 按指定公网地址查询，供应商连接使用另一地址族也不能改变本次探测身份。
    if public_address(record.get("traits", {}).get("ip_address"), config["family"]) != address:
        raise ValueError("Provider address mismatch")
    return geo_fields(record), config["web_host"] + ":v2.1"


def observe(node: str, report_id: str, config: dict, *, egress=read_egress, lookup=locate) -> dict:
    """定位前后核对出口，变化或探测失败时禁用旧出口缓存。"""
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", node) is None or len(config["fingerprint_key"]) < 32 \
            or config["family"] not in IP_ENDPOINTS or config["provider"] not in {"MAXMIND_MMDB", "MAXMIND_WEB"}:
        raise ValueError("Invalid node region configuration")
    client = network_client(config["proxy"])
    report = {"id": str(uuid.UUID(report_id)), "nodeId": node, "status": "EGRESS_UNAVAILABLE",
              "egressFingerprint": None, "addressFamily": None, "networkMode": "EXPLICIT_PROXY" if config["proxy"] else "DIRECT",
              "provider": config["provider"], "providerVersion": "UNAVAILABLE", "geo": None}
    try:
        address = egress(client, config["family"])
        try:
            geo, version = lookup(address, config, client)
        except Exception:
            geo, version = None, "UNAVAILABLE"
        if egress(client, config["family"]) == address:
            # HMAC 防止低熵 IPv4 地址被离线字典还原，密钥不离开节点环境。
            identity = "v1\n" + node + "\n" + config["family"] + "\n" + report["networkMode"] + "\n" + str(address)
            report.update(status="RESOLVED" if geo is not None else "LOOKUP_FAILED", geo=geo,
                          providerVersion=version, addressFamily=config["family"],
                          egressFingerprint=hmac.new(config["fingerprint_key"].encode(), identity.encode(), hashlib.sha256).hexdigest())
    except Exception:
        pass  # 失败报告不回显异常，异常中可能包含请求地址或原始公网 IP。
    report["observedAt"] = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    return report


def job_request(job_id, execution_id, lease, base_url, token, action, report=None, *, sender=request_json):
    """通过固定作业入口传递短期执行租约，不将凭据写入结果。"""
    parsed = urllib.parse.urlsplit(base_url)
    if len(token) < 32 or parsed.username or parsed.password or parsed.query or parsed.fragment \
            or parsed.path not in {"", "/"} or not parsed.hostname or any(c.isspace() for c in base_url) \
            or not (parsed.scheme == "https" or (parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "::1"})):
        raise ValueError("Invalid report destination")
    for value in (job_id, execution_id, lease):
        if str(uuid.UUID(value)) != value:
            raise ValueError("Invalid job identity")
    if action not in {"receipt", "observation"}:
        raise ValueError("Invalid job action")
    result = sender(network_client(), base_url.rstrip("/") + "/internal/jobs/news/region-jobs/" + job_id + "/" + action,
                    headers={"Authorization": "Bearer " + token, "X-Task-Execution-Id": execution_id, "X-Task-Lease-Token": lease}, data=report or {})
    if action == "receipt":
        if set(result) != {"receipt"}:
            raise ValueError("Invalid receipt envelope")
        result = result["receipt"]
        if result is None:
            return None
    if not isinstance(result, dict) or set(result) != {"jobId", "observationId", "status", "observationStatus"} \
            or result["jobId"] != job_id or result["status"] != "STORED" \
            or result["observationStatus"] not in {"RESOLVED", "LOOKUP_FAILED", "EGRESS_UNAVAILABLE"}:
        raise ValueError("Invalid region receipt")
    if str(uuid.UUID(result["observationId"])) != result["observationId"]:
        raise ValueError("Invalid observation identity")
    if report is not None and result["observationId"] != report["id"]:
        raise ValueError("Mismatched observation receipt")
    return result


def write_json(path: Path, value: dict) -> None:
    """原子写入仅当前用户可读的日志或结果，不写原始 IP。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, encoding="utf-8", delete=False) as handle:
        json.dump(value, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(path)


def main() -> int:
    """在当前 Executor 网络环境执行一次探测，结果不携带地址或凭据。"""
    try:
        config = {"provider": os.getenv("NEWS_REGION_PROVIDER", "MAXMIND_MMDB"),
                  "family": os.getenv("NEWS_REGION_ADDRESS_FAMILY", "IPV4"),
                  "proxy": os.getenv("NEWS_REGION_PROXY_URL", ""), "mmdb": os.getenv("NEWS_REGION_MMDB_PATH", ""),
                  "account": os.getenv("NEWS_REGION_MAXMIND_ACCOUNT", ""), "license": os.getenv("NEWS_REGION_MAXMIND_LICENSE", ""),
                  "web_host": os.getenv("NEWS_REGION_MAXMIND_HOST", "geolite.info"),
                  "fingerprint_key": os.getenv("NEWS_REGION_FINGERPRINT_KEY", "")}
        node = os.environ["TASK_EXECUTOR_NODE_AFFINITY"]
        report_id = str(uuid.UUID(os.environ["TASK_EXECUTION_ID"]))
        context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
        if context.get("taskName") != "news_resolve_region" or context.get("stepName") != "resolve" \
                or context.get("executionId") != report_id or set(context.get("parameters", {})) != {"jobId"}:
            raise ValueError("Invalid job context")
        job_id = context["parameters"]["jobId"]
        lease = Path(os.environ["TASK_LEASE_TOKEN_FILE"]).read_text(encoding="utf-8").strip()
        base_url, token = os.getenv("NEWS_SERVICE_URL", "http://127.0.0.1:23350"), os.getenv("NEWS_JOB_TOKEN", "")
        # 原结果先查询，响应丢失或新执行重试时不重新探测改变出口证据。
        prior = job_request(job_id, report_id, lease, base_url, token, "receipt")
        if prior is not None:
            write_json(Path(os.environ["TASK_RESULT_FILE"]), prior)
            return 0
        journal = Path(os.environ["TASK_WORK_DIR"]) / "region-observation.json"
        if journal.exists():
            report = json.loads(journal.read_text(encoding="utf-8"))
            if report.get("id") != report_id or report.get("nodeId") != node:
                raise ValueError("Journal identity mismatch")
        else:
            report = observe(node, report_id, config)
            write_json(journal, report)
        result = job_request(job_id, report_id, lease, base_url, token, "observation", report)
        write_json(Path(os.environ["TASK_RESULT_FILE"]), result)
        return 0
    except Exception:
        print('{"status":"FAILED","code":"REGION_PROBE_FAILED"}', file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
