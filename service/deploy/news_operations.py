#!/usr/bin/env python3
"""读取新闻聚合状态；仅在回环接口显式执行有界保留清理。"""
import argparse
import ipaddress
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """禁止通过重定向转发管理凭据。"""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_plan(args):
    """读取凭据前先验证本机管理入口。"""
    url = urllib.parse.urlsplit(args.url)
    try:
        loopback = ipaddress.ip_address(url.hostname or "").is_loopback
    except ValueError:
        loopback = False
    if url.scheme != "http" or not loopback or url.username or url.password or url.query or url.fragment or url.path not in ("", "/"):
        raise ValueError("NEWS_OPS_LOCAL_ENDPOINT_REQUIRED")
    body = None
    if args.action in ("status", "preview"):
        path, method = ("/operations" if args.action == "status" else "/retention/preview"), "GET"
    elif args.action == "sweep":
        path, method = "/retention/sweep", "POST"
    else:
        if not args.source_id or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", args.source_id) or not 1 <= (args.evidence_hours or 0) <= 168:
            raise ValueError("NEWS_OPS_SOURCE_POLICY_INVALID")
        path, method = f"/sources/{args.source_id}/retention", "PUT"
        body = json.dumps({"evidenceHours": args.evidence_hours}).encode()
    return args.url.rstrip("/") + "/internal/admin" + path, method, body


def validate_response(action, data):
    """逐层验证脱敏聚合字段，拒绝意外正文或地址。"""
    numbers = {"articleEvidence", "githubEvidence", "articleMetadata", "githubSnapshots", "editions", "deliveryBodies", "evidenceHours"}
    metrics = {"retention_failed", "sources_enabled", "sources_failed", "collection_overdue", "task_dead", "generation_pending", "delivery_unknown", "delivery_rejected", "delivery_pending", "delivery_oldest_pending_seconds", "retention_oldest_article_evidence_due_seconds"}
    def numeric(value, keys):
        return isinstance(value, dict) and set(value).issubset(keys) and all(type(item) is int and item >= 0 for item in value.values())
    if action != "status":
        valid = numeric(data, numbers)
    else:
        allowed = {"observedAt", "retentionEnabled", "counts", "retentionDue", "retentionCheckpoint", "retentionPolicy"}
        valid = isinstance(data, dict) and set(data).issubset(allowed) and numeric(data.get("counts"), metrics)
        if valid and "observedAt" in data:
            valid = isinstance(data["observedAt"], str) and bool(re.fullmatch(r"[0-9T:.+Z-]{20,40}", data["observedAt"]))
        if valid and "retentionEnabled" in data:
            valid = type(data["retentionEnabled"]) is bool
        if valid and "retentionDue" in data:
            valid = numeric(data["retentionDue"], numbers)
        if valid and "retentionPolicy" in data:
            valid = numeric(data["retentionPolicy"], {"evidenceHours", "metadataDays", "editionDays", "deliveryDays", "batchSize"})
        if valid and "retentionCheckpoint" in data:
            check = data["retentionCheckpoint"]
            valid = isinstance(check, dict) and set(check).issubset({"last_started_ms", "last_finished_ms", "last_status", "counts_json"})
            if valid:
                valid = check.get("last_status") in {"NEVER_RUN", "RUNNING", "SUCCEEDED", "FAILED"} and all(check.get(key) is None or type(check[key]) is int for key in ("last_started_ms", "last_finished_ms"))
                if valid and check.get("counts_json") is not None:
                    valid = isinstance(check["counts_json"], str) and numeric(json.loads(check["counts_json"]), numbers)
    if not valid:
        raise ValueError("NEWS_OPS_RESPONSE_INVALID")


def main(argv=None):
    """默认只读，修改必须显式携带执行标记。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:23350")
    parser.add_argument("--action", choices=("status", "preview", "sweep", "source-policy"), default="status")
    parser.add_argument("--source-id")
    parser.add_argument("--evidence-hours", type=int)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    try:
        url, method, body = request_plan(args)
        if method != "GET" and not args.execute:
            print(json.dumps({"action": args.action, "executeRequired": True, "changed": False}))
            return 0
        token = os.environ.get("NEWS_ADMIN_TOKEN", "")
        if len(token) < 32 or "\r" in token or "\n" in token:
            raise ValueError("NEWS_OPS_ADMIN_TOKEN_REQUIRED")
        request = urllib.request.Request(url, data=body, method=method, headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(request, timeout=40) as response:
            raw = response.read(65537)
            if len(raw) > 65536:
                raise ValueError("NEWS_OPS_RESPONSE_TOO_LARGE")
            data = json.loads(raw)
        validate_response(args.action, data)
        print(json.dumps(data, ensure_ascii=True, sort_keys=True))
        return 0
    except urllib.error.HTTPError as error:
        print(json.dumps({"error": f"NEWS_OPS_HTTP_{error.code}"}), file=sys.stderr)
    except ValueError as error:
        message = str(error)
        print(json.dumps({"error": message if message.startswith("NEWS_OPS_") else "NEWS_OPS_RESPONSE_INVALID"}), file=sys.stderr)
    except (OSError, urllib.error.URLError):
        print(json.dumps({"error": "NEWS_OPS_UNAVAILABLE"}), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
