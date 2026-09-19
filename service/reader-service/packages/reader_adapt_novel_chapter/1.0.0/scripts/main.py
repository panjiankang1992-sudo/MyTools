#!/usr/bin/env python3
"""在受限 rootfs 内只请求一次宿主执行，不持有正文、服务令牌或模型凭据。"""

import json
import os
import re
import socket
import sys


def unique_object(pairs):
    """拒绝重复键，避免宿主结果被后一个同名字段覆盖。"""
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Invalid broker reply")
        result[key] = value
    return result


def invoke(directory="/work"):
    """只发送固定 run 和一次性 handle，所有未知故障仅返回失败退出码。"""
    try:
        descriptor = os.open(directory + "/handle", os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, "rb") as source:
            handle = source.read(64).decode("ascii")
        if re.fullmatch(r"[A-Za-z0-9_-]{43}", handle) is None:
            return 1
        request = json.dumps({"op": "run", "handle": handle}, separators=(",", ":")).encode("ascii") + b"\n"
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
            connection.settimeout(905)
            connection.connect(directory + "/b.sock")
            connection.sendall(request)
            response = bytearray()
            while b"\n" not in response:
                chunk = connection.recv(1025 - len(response))
                if not chunk:
                    return 1
                response.extend(chunk)
                if len(response) > 1024:
                    return 1
        if response[-1:] != b"\n" or response.count(b"\n") != 1:
            return 1
        result = json.loads(response.decode("utf-8"), object_pairs_hook=unique_object)
        if not isinstance(result, dict) or set(result) != {"status", "errorCode"}:
            return 1
        if result["status"] not in {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"}:
            return 1
        # 脚本不保存结果正文，正式采用和版本历史均由宿主与 Reader 完成。
        return 0 if result["status"] == "SUCCEEDED" and result["errorCode"] is None else 1
    except Exception:
        return 1


def main():
    """受限生产入口不接受自定义目录、URL、正文或其他命令行参数。"""
    return 1 if len(sys.argv) != 1 else invoke()


if __name__ == "__main__":
    raise SystemExit(main())
