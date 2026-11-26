#!/usr/bin/env python3
"""只读检查运行进程中的新闻环境，不回显配置值或将其冒充 Spring 最终绑定。"""
from __future__ import annotations

import argparse
import base64
import binascii
from dataclasses import dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True

# 只检查固定的业务服务，不能通过参数指定任意进程或命令。
UNITS = {
    "news": "mytools-news-digest-service.service", "gateway": "mytools-mytools-gateway.service",
    "scheduler": "mytools-task-scheduler-service.service", "executor": "mytools-task-executor-service.service",
    "messaging": "mytools-messaging-service.service",
}
REQUIRED = {
    "news": ("NEWS_INTERNAL_TOKEN", "NEWS_ADMIN_TOKEN", "NEWS_JOB_TOKEN", "NEWS_REGION_REPORT_TOKEN",
             "TASK_BUSINESS_NEWS_TOKEN", "MESSAGING_OUTBOUND_CLIENT_TOKEN", "NEWS_DB_USER", "NEWS_DB_PASSWORD"),
    "gateway": ("NEWS_INTERNAL_TOKEN",),
    "scheduler": ("TASK_BUSINESS_NEWS_TOKEN",),
    "executor": ("NEWS_JOB_TOKEN", "NEWS_REGION_FINGERPRINT_KEY", "MESSAGING_OUTBOUND_WORKER_TOKEN"),
    "messaging": ("MESSAGING_OUTBOUND_CLIENT_TOKEN", "MESSAGING_OUTBOUND_WORKER_TOKEN",
                  "MESSAGING_OUTBOUND_ADMIN_TOKEN", "MESSAGING_DESTINATION_ENCRYPTION_KEY"),
}
BINDINGS = (
    ("news_gateway", "news", "gateway", "NEWS_INTERNAL_TOKEN"),
    ("news_scheduler", "news", "scheduler", "TASK_BUSINESS_NEWS_TOKEN"),
    ("news_executor", "news", "executor", "NEWS_JOB_TOKEN"),
    ("news_messaging", "news", "messaging", "MESSAGING_OUTBOUND_CLIENT_TOKEN"),
    ("executor_messaging", "executor", "messaging", "MESSAGING_OUTBOUND_WORKER_TOKEN"),
)
PACKAGES = {"news_generate_edition": "1.0.0", "news_resolve_region": "1.2.0",
            "message_send_channel_message": "1.0.0"}
MAX_BYTES = 1024 * 1024
CHILD_ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8"}


class InspectionError(Exception):
    """所有外显失败只包含固定错误码，不包含系统异常或配置内容。"""


@dataclass(repr=False)
class Snapshot:
    """私有进程快照仅驻留内存，日志表示不包含凭据。"""
    pid: int
    start: str
    values: dict[str, str]
    arguments: tuple[str, ...]
    non_root: bool

    def __repr__(self) -> str:
        return "Snapshot[redacted]"


def unit_state(unit: str) -> tuple[str, int]:
    """仅读取固定 systemd 状态字段，禁止返回 ExecStart 或 Environment 原文。"""
    try:
        result = subprocess.run(["systemctl", "show", unit, "--no-pager",
                                 "--property=LoadState,ActiveState,MainPID"], env=CHILD_ENV,
                                stdin=subprocess.DEVNULL, capture_output=True, timeout=5, check=False)
        if len(result.stdout) > 4096 or result.returncode != 0:
            raise ValueError()
        values = dict(line.split("=", 1) for line in result.stdout.decode().splitlines())
        if values.get("LoadState") == "not-found":
            return "NOT_INSTALLED", 0
        if values.get("LoadState") != "loaded":
            return "UNAVAILABLE", 0
        pid = int(values["MainPID"])
        return ("RUNNING", pid) if values.get("ActiveState") == "active" and pid > 0 else ("NOT_RUNNING", 0)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        raise InspectionError("NEWS_RUNTIME_SYSTEMD_UNAVAILABLE") from error


def read_bounded(path: Path) -> bytes:
    """限制 procfs 读取大小；不把内容、路径或系统异常放进报告。"""
    with path.open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError()
    return raw


def start_stamp(directory: Path) -> str:
    """使用进程启动时钟区分 PID 复用，名称包含括号也能正确解析。"""
    raw = read_bounded(directory / "stat").decode("ascii")
    stamp = raw.rsplit(") ", 1)[1].split()[19]
    if not stamp.isdigit():
        raise ValueError()
    return stamp


def snapshot(pid: int, proc_root: Path = Path("/proc")) -> Snapshot:
    """在读取前后检查进程身份，环境重复键及非法编码均使检查失败。"""
    try:
        directory = proc_root / str(pid)
        before = start_stamp(directory)
        values = {}
        for item in read_bounded(directory / "environ").split(b"\0"):
            if not item:
                continue
            key, separator, value = item.decode("utf-8").partition("=")
            if not separator or not key or key in values:
                raise ValueError()
            values[key] = value
        arguments = tuple(item.decode("utf-8") for item in read_bounded(directory / "cmdline").split(b"\0") if item)
        status = dict(line.split(":", 1) for line in read_bounded(directory / "status").decode().splitlines() if ":" in line)
        user_ids = [int(value) for value in status["Uid"].split()]
        if len(user_ids) != 4 or before != start_stamp(directory):
            raise ValueError()
        return Snapshot(pid, before, values, arguments, all(value > 0 for value in user_ids))
    except (OSError, ValueError, KeyError, IndexError) as error:
        raise InspectionError("NEWS_RUNTIME_PROCESS_UNREADABLE_OR_CHANGED") from error


def override_signals(item: Snapshot) -> list[str]:
    """指出高优先级配置入口；没有信号也不代表已解析 Spring 全部配置源。"""
    reasons = set()
    prefixes = ("news.", "gateway.", "executor.", "task.", "messaging.", "spring.")
    for argument in item.arguments:
        if argument.startswith("--") and argument[2:].startswith(prefixes):
            reasons.add("COMMAND_LINE_PROPERTIES")
        if argument.startswith("-D") and argument[2:].startswith(prefixes):
            reasons.add("JVM_SYSTEM_PROPERTIES")
    for key, reason in (("SPRING_APPLICATION_JSON", "SPRING_JSON"),
                        ("SPRING_CONFIG_LOCATION", "EXTERNAL_SPRING_CONFIG"),
                        ("SPRING_CONFIG_ADDITIONAL_LOCATION", "EXTERNAL_SPRING_CONFIG"),
                        ("SPRING_CONFIG_IMPORT", "EXTERNAL_SPRING_CONFIG"),
                        ("SPRING_PROFILES_ACTIVE", "ACTIVE_SPRING_PROFILES"),
                        ("JAVA_TOOL_OPTIONS", "JVM_OPTIONS_ENVIRONMENT"),
                        ("JDK_JAVA_OPTIONS", "JVM_OPTIONS_ENVIRONMENT"),
                        ("_JAVA_OPTIONS", "JVM_OPTIONS_ENVIRONMENT")):
        if item.values.get(key):
            reasons.add(reason)
    return sorted(reasons)


def candidate_file(value: str, *parts: str) -> bool:
    """仅返回候选路径是否存在，不执行环境指定的解释器或读取该文件。"""
    try:
        path = Path(value)
        return bool(value) and path.is_absolute() and path.joinpath(*parts).is_file()
    except (OSError, ValueError):
        return False


def inspect_role(role: str, item: Snapshot) -> dict:
    """仅固定键名及布尔/枚举状态能进入公开报告。"""
    missing = [key for key in REQUIRED[role] if not item.values.get(key)]
    invalid = []
    for key in REQUIRED[role]:
        value = item.values.get(key, "")
        if value and (key.endswith("_TOKEN") or key == "NEWS_REGION_FINGERPRINT_KEY"):
            if len(value) < 32 or any(c.isspace() or ord(c) < 32 for c in value) or "${" in value:
                invalid.append(key)
        if value and key == "MESSAGING_DESTINATION_ENCRYPTION_KEY":
            try:
                if len(base64.b64decode(value, validate=True)) != 32:
                    invalid.append(key)
            except (ValueError, binascii.Error):
                invalid.append(key)
    result = {"state": "RUNNING", "nonRoot": item.non_root, "missingEnvironmentKeys": missing,
              "invalidEnvironmentKeys": invalid, "overrideSignals": override_signals(item)}
    if role == "executor":
        root = item.values.get("TASK_EXECUTOR_SCRIPT_ROOT", "")
        provider = item.values.get("NEWS_REGION_PROVIDER", "MAXMIND_MMDB")
        geo = (candidate_file(item.values.get("NEWS_REGION_MMDB_PATH", "")) if provider == "MAXMIND_MMDB"
               else bool(item.values.get("NEWS_REGION_MAXMIND_ACCOUNT", "").isdigit()
                         and item.values.get("NEWS_REGION_MAXMIND_LICENSE")
                         and item.values.get("NEWS_REGION_MAXMIND_HOST", "geolite.info") in {"geolite.info", "geoip.maxmind.com"})
               if provider == "MAXMIND_WEB" else provider == "IPWHOIS")
        result["executorCandidates"] = {
            "packageFilesPresent": {name: candidate_file(root, name, version, "scripts", "main.py")
                                    for name, version in PACKAGES.items()},
            "packageIndexPresent": candidate_file(root, "package-index.json"),
            "geolocationPrerequisitePresent": geo,
            "explicitRegionProxyPresent": bool(item.values.get("NEWS_REGION_PROXY_URL")),
            "networkIsolationEnvironment": {"true": "ENABLED", "false": "DISABLED", "": "UNSET"}
                .get(item.values.get("TASK_EXECUTOR_NETWORK_ISOLATION_ENABLED", "").lower(), "INVALID"),
            "servicePermissionsVerified": False, "packageIntegrityVerified": False,
        }
    return result


def inspect() -> dict:
    """采集所有角色后再次核对 MainPID/启动时间，拒绝跨服务重启时拼接旧快照。"""
    snapshots = {}
    states = {}
    for role, unit in UNITS.items():
        state, pid = unit_state(unit)
        states[role] = (state, pid)
        if pid:
            snapshots[role] = snapshot(pid)
    for role, unit in UNITS.items():
        if unit_state(unit) != states[role]:
            raise InspectionError("NEWS_RUNTIME_CHANGED_DURING_INSPECTION")
        if role in snapshots:
            try:
                if start_stamp(Path("/proc") / str(snapshots[role].pid)) != snapshots[role].start:
                    raise ValueError()
            except (OSError, ValueError, IndexError) as error:
                raise InspectionError("NEWS_RUNTIME_CHANGED_DURING_INSPECTION") from error
    roles = {role: inspect_role(role, snapshots[role]) if role in snapshots else {"state": states[role][0]}
             for role in UNITS}
    bindings = {}
    for name, left, right, key in BINDINGS:
        if left not in snapshots or right not in snapshots:
            bindings[name] = "PROCESS_UNAVAILABLE"
        else:
            a, b = snapshots[left].values.get(key), snapshots[right].values.get(key)
            bindings[name] = "MISSING" if not a or not b else "MATCH" if a == b else "MISMATCH"
    node_keys = (("news", "NEWS_REGION_NODE_ID"), ("scheduler", "TASK_NEWS_NODE_VALUE"),
                 ("executor", "TASK_NEWS_NODE_VALUE"), ("executor", "TASK_EXECUTOR_NODE_NAME"))
    nodes = [snapshots[role].values.get(key, "") if role in snapshots else "" for role, key in node_keys]
    binding = "MISSING" if not all(nodes) else "MATCH" if len(set(nodes)) == 1 and nodes[0] != "absent" else "MISMATCH"
    return {"inspectionCompleted": True, "checkedAt": datetime.now(timezone.utc).isoformat(),
            "scope": "PROCESS_ENVIRONMENT_ONLY", "roles": roles,
            "environmentCredentialBindings": bindings, "environmentNodeBinding": binding,
            "rawEnvironmentReturned": False, "productionChanges": False,
            "springBindingVerified": False, "liveAcceptance": False}


def main(argv=None) -> int:
    """退出码只表示能否完整检查，不表示系统具备上线条件。"""
    argparse.ArgumentParser(description=__doc__).parse_args(argv)
    try:
        result = inspect()
        print(json.dumps(result, sort_keys=True))
        return 0
    except (InspectionError, OSError, ValueError) as error:
        code = str(error) if isinstance(error, InspectionError) else "NEWS_RUNTIME_INSPECTION_FAILED"
        print(json.dumps({"inspectionCompleted": False, "code": code, "rawEnvironmentReturned": False}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
