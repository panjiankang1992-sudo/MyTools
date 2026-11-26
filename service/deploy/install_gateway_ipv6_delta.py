"""以当前运行包为基线安装 IPv6 协商增量，并提供独立回退配置。"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import urllib.request

from apply_gateway_ipv6_delta import patch


UNIT = "mytools-mytools-gateway.service"


def run(*args: str) -> str:
    """执行本机操作，避免将可能包含运行参数的原始输出打印到日志。"""
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def health() -> None:
    """等待现有回环健康接口恢复，失败由安装流程撤销本次配置。"""
    for _ in range(40):
        try:
            with urllib.request.urlopen("http://127.0.0.1:23200/actuator/health", timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("Gateway health check timed out")


def main() -> None:
    """安装不可变增量，保留旧工件及其他 systemd 配置。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--delta", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--origin", required=True)
    args = parser.parse_args()
    if os.geteuid() != 0 or not re.fullmatch(r"https://(?:[a-z0-9-]+\.)+[a-z]{2,63}(?::8443)?", args.origin):
        raise ValueError("Invalid installation parameters")
    dropin = Path("/etc/systemd/system") / (UNIT + ".d") / "zzzzzzzzzz-ipv6-direct-20261103.conf"
    if args.release.exists() or dropin.exists():
        raise ValueError("Refusing to overwrite an existing release")
    start = run("systemctl", "show", UNIT, "-p", "ExecStart", "--value")
    match = re.search(r"argv\[\]=(.*?) ; ignore_errors=", start)
    if match is None:
        raise ValueError("Unable to locate current Gateway artifact")
    command = shlex.split(match.group(1))
    index = command.index("-jar") + 1
    base = Path(command[index])
    args.release.mkdir(parents=True, mode=0o755)
    output = args.release / "mytools-gateway.jar"
    receipt = patch(base, args.delta, output, args.sha256)
    # 在写入服务配置前再次核对当前进程命令，拒绝覆盖并发发布。
    if run("systemctl", "show", UNIT, "-p", "ExecStart", "--value") != start:
        raise RuntimeError("Gateway changed during preparation")
    command[index] = str(output)
    command = [value for value in command if not value.startswith("--gateway.connectivity.ipv6-direct-origins=")]
    command.append("--gateway.connectivity.ipv6-direct-origins=" + args.origin)
    serialized = " ".join(json.dumps(value.replace("%", "%%")) for value in command)
    dropin.parent.mkdir(parents=True, exist_ok=True)
    try:
        with dropin.open("x") as stream:
            stream.write("[Service]\nExecStart=\nExecStart=" + serialized + "\n")
        dropin.chmod(0o600)
        run("systemctl", "daemon-reload")
        run("systemctl", "restart", UNIT)
        health()
        if str(output) not in run("systemctl", "show", UNIT, "-p", "ExecStart", "--value"):
            raise RuntimeError("New service command was overridden")
    except Exception:
        dropin.unlink(missing_ok=True)
        run("systemctl", "daemon-reload")
        run("systemctl", "restart", UNIT)
        health()
        raise
    receipt.update({"gatewayHealthy": True, "directOrigin": args.origin,
                    "dropIn": str(dropin), "release": str(args.release)})
    (args.release / "deployment.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"deployed": False, "errorType": type(error).__name__}))
        raise SystemExit(1) from None
