#!/usr/bin/env python3
"""只读抓取公开教材基线；不登录、不发送写请求、不保存 cookie。"""

import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import urllib.parse


def digest(value):
    """对排序后的 JSON 计算内容哈希，忽略传输格式。"""
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(raw).hexdigest()


def request(base, path):
    """仅发起匿名 GET，不接受任何身份或写操作参数。"""
    response = subprocess.run([
        "curl", "--silent", "--show-error", "--max-time", "40",
        "--header", "Cache-Control: no-cache", "--request", "GET",
        "--write-out", "\n%{http_code}\t%{content_type}", base + path,
    ], check=True, capture_output=True)
    body, trailer = response.stdout.rsplit(b"\n", 1)
    status, content_type = trailer.decode().split("\t", 1)
    try:
        parsed = json.loads(body)
    except (UnicodeDecodeError, ValueError):
        parsed = None
    return int(status), content_type, body, parsed


def payload(base, path):
    """读取成功的公开响应信封；失败即中止验收。"""
    status, _, _, body = request(base, path)
    if status != 200 or not isinstance(body, dict) or body.get("code") != "OK":
        raise RuntimeError(f"Unexpected response: {path}, HTTP {status}")
    return body["data"]


def capture(base):
    """保存所有已发布教材元数据，以及完整分页条目的稳定哈希。"""
    home_status, home_type, home_body, _ = request(base, "/")
    admin_status, _, _, admin_body = request(base, "/admin/v1/textbooks")
    health_status, health_type, _, health_body = request(base, "/public/v1/health")
    catalog = payload(base, "/public/v1/textbooks")
    books = []
    for edition in sorted(catalog["editions"], key=lambda row: row["editionId"]):
        edition_id = edition["editionId"]
        version = edition["contentVersion"]
        path = "/public/v1/textbooks/" + urllib.parse.quote(edition_id, safe="")
        path += "/versions/" + urllib.parse.quote(version, safe="")
        structure = payload(base, path)
        entries = []
        cursor = None
        cursors = set()
        page_count = 0
        while True:
            params = {"limit": 500}
            if cursor:
                params["cursor"] = cursor
            page = payload(base, path + "/entries?" + urllib.parse.urlencode(params))
            if page["contentVersion"] != version:
                raise RuntimeError(f"Content version changed: {edition_id}")
            entries.extend(page["entries"])
            page_count += 1
            cursor = page.get("nextCursor")
            if not cursor:
                break
            if cursor in cursors or page_count >= 200:
                raise RuntimeError(f"Invalid pagination: {edition_id}")
            cursors.add(cursor)
        if len(entries) != page["total"] or len(entries) != structure["entryCount"]:
            raise RuntimeError(f"Incomplete entries: {edition_id}")
        if len({row["entryId"] for row in entries}) != len(entries):
            raise RuntimeError(f"Duplicate entry IDs: {edition_id}")
        canonical_entries = sorted(entries, key=lambda row: row["entryId"])
        books.append({
            "editionId": edition_id,
            "contentVersion": version,
            "metadata": edition,
            "structure": structure,
            "metadataSha256": digest(edition),
            "structureSha256": digest(structure),
            "entryCount": len(entries),
            "entriesSha256": digest(canonical_entries),
            "entryHashes": {row["entryId"]: digest(row) for row in canonical_entries},
            "pageCount": page_count,
        })
    # 抓取期间如有管理员发布，不能把两个时点的数据混为同一份基线。
    catalog_end = payload(base, "/public/v1/textbooks")
    for key in ("editions", "coverage"):
        if digest(catalog[key]) != digest(catalog_end[key]):
            raise RuntimeError("Catalog changed during capture; rerun after publishing finishes")
    home_text = home_body.decode("utf-8", errors="replace")
    health_json = isinstance(health_body, dict) and health_body.get("status") is not None
    result = {
        "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "baseUrl": base,
        "requestPolicy": "anonymous GET only; no login, cookies, or data writes",
        "home": {"status": home_status, "contentType": home_type,
                 "mainAssets": re.findall(r'(?:src|href)="(/assets/index-[^"]+)"', home_text)},
        "adminAnonymous": {"status": admin_status, "code": (admin_body or {}).get("code")},
        "health": {"publicCurriculumReady": True, "path": "/public/v1/health", "httpStatus": health_status,
                   "contentType": health_type, "status": health_body.get("status") if health_json else None},
        "catalogVersionInformational": catalog.get("catalogVersion"),
        "coverage": catalog["coverage"],
        "coverageSha256": digest(catalog["coverage"]),
        "bookCount": len(books),
        "entryCount": sum(book["entryCount"] for book in books),
        "books": books,
    }
    if home_status != 200 or admin_status != 401:
        raise RuntimeError(f"Home or anonymous protection failed: {home_status}, {admin_status}")
    if health_status != 200 or not health_json or health_body["status"] != "UP":
        raise RuntimeError("Public health check failed")
    return result


def compare(before, after):
    """只比较教材内容；catalogVersion 可因重启改变。"""
    old = {book["editionId"]: book for book in before["books"]}
    new = {book["editionId"]: book for book in after["books"]}
    changes = []
    if old.keys() != new.keys():
        changes.append({"field": "editionIds", "before": sorted(old), "after": sorted(new)})
    for key in sorted(old.keys() & new.keys()):
        for field in ("contentVersion", "metadataSha256", "structureSha256", "entryCount", "entriesSha256"):
            if old[key][field] != new[key][field]:
                changes.append({"editionId": key, "field": field, "before": old[key][field], "after": new[key][field]})
    if before["coverageSha256"] != after["coverageSha256"]:
        changes.append({"field": "coverageSha256"})
    return {"unchanged": not changes, "changes": changes, "comparedBookCount": len(old),
            "comparedEntryCount": before["entryCount"], "ignoredFields": ["catalogVersionInformational", "capturedAt", "home.mainAssets"]}


def capture_server(ssh_host):
    """通过当次已确认的 SSH 别名只读核对部署文件，不读取配置和凭据。"""
    code = '''import datetime, hashlib, json
from pathlib import Path
root = Path("/opt/yuyutian/toolbox")
print(json.dumps({
    "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "frontendIndexSha256": hashlib.sha256((root / "frontend/index.html").read_bytes()).hexdigest(),
    "backendJarSha256": hashlib.sha256((root / "backend/toolbox-service.jar").read_bytes()).hexdigest()
}))
'''
    result = subprocess.run([
        "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", ssh_host, "sudo", "-n", "python3", "-",
    ], input=code, check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def main():
    """按 before 或 after 模式保存复核证据。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["before", "after"])
    parser.add_argument("--base-url", default="https://toolbox.yuyutian.top")
    parser.add_argument("--ssh-host", help="Current local SSH alias, used only for deployed-file hashes")
    parser.add_argument("--expected-backend-sha256", help="Expected new backend artifact hash for this authorized release")
    parser.add_argument("--expected-index-sha256", help="Expected new frontend index hash for this authorized release")
    args = parser.parse_args()
    for value in (args.expected_backend_sha256, args.expected_index_sha256):
        if value and not re.fullmatch(r"[0-9a-f]{64}", value):
            parser.error("Expected artifact hashes must be lowercase SHA256 hex strings")
    if (args.expected_backend_sha256 or args.expected_index_sha256) and not args.ssh_host:
        parser.error("Expected artifact hashes require --ssh-host")
    folder = Path(__file__).resolve().parent
    result = capture(args.base_url.rstrip("/"))
    if args.ssh_host:
        result["server"] = capture_server(args.ssh_host)
    if args.mode == "after":
        before = json.loads((folder / "public-before.json").read_text())
        result["comparison"] = compare(before, result)
        if "server" in before and "server" in result:
            result["comparison"]["backendJarUnchanged"] = before["server"]["backendJarSha256"] == result["server"]["backendJarSha256"]
        if args.expected_backend_sha256:
            result["comparison"]["backendJarMatchesRelease"] = result["server"]["backendJarSha256"] == args.expected_backend_sha256
        if args.expected_index_sha256:
            result["comparison"]["frontendIndexMatchesRelease"] = result["server"]["frontendIndexSha256"] == args.expected_index_sha256
    output = folder / f"public-{args.mode}.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"output": str(output), "bookCount": result["bookCount"], "entryCount": result["entryCount"],
                      "adminAnonymousStatus": result["adminAnonymous"]["status"], "comparison": result.get("comparison")}, ensure_ascii=False))
    comparison = result.get("comparison", {})
    # 本轮同时更新前后端，后端与旧版不同是预期；必须与指定的新产物匹配。
    if comparison.get("unchanged") is False or comparison.get("backendJarMatchesRelease") is False or comparison.get("frontendIndexMatchesRelease") is False:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
