#!/usr/bin/env python3
"""只为已有私有环境补齐新闻缺失项；默认预览，不替换既有值。"""
from __future__ import annotations

import argparse
import base64
import fcntl
import json
import os
from pathlib import Path
import secrets
import stat
import sys
import tempfile
import uuid

# 发布工具不能因导入相邻模块而改变已验证的发布目录。
sys.dont_write_bytecode = True

from news_deployment_config import (ALL_KEYS, DEFAULTS, MAIN_FLAGS, MAX_ENV_BYTES, SECRET_KEYS,
                                    NewsConfigurationError, PrivateEnvironment, flag,
                                    read_private, unchanged)


def plan(original: PrivateEnvironment) -> dict:
    """计划只返回本功能的配置键，空值仍保留并交由检查器报告。"""
    missing = sorted(ALL_KEYS - original.values.keys())
    if missing:
        enabled = tuple(key for key in MAIN_FLAGS if flag(original.values, key))
        if enabled:
            raise NewsConfigurationError("NEWS_CONFIG_UPGRADE_REQUIRES_DISABLED", enabled)
    return {"addKeys": missing, "preservedKeys": sorted(ALL_KEYS & original.values.keys()),
            "emptyRequiredKeys": sorted(key for key in SECRET_KEYS + ("NEWS_DB_USER",)
                                        if key in original.values and not original.values[key]),
            "changed": False}


def additions(original: PrivateEnvironment, keys: list[str]) -> dict[str, str]:
    """仅在显式执行且锁定文件后生成缺失秘密，不自动修复空密钥。"""
    result: dict[str, str] = {}
    occupied = set(original.values.values())
    for key in keys:
        if key in SECRET_KEYS:
            while True:
                value = base64.b64encode(secrets.token_bytes(32)).decode("ascii") if key == "MESSAGING_DESTINATION_ENCRYPTION_KEY" else secrets.token_urlsafe(32)
                if value not in occupied:
                    break
            occupied.add(value)
        else:
            value = DEFAULTS[key]
        result[key] = value
    return result


def private_write(descriptor: int, data: bytes, original: PrivateEnvironment) -> None:
    """临时文件与备份保持原属主、属组和精确私有权限。"""
    with os.fdopen(descriptor, "wb") as stream:
        os.fchown(stream.fileno(), original.uid, original.gid)
        os.fchmod(stream.fileno(), 0o600)
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def apply(path: Path) -> dict:
    """通过独立文件锁及提交前复核执行一次原子增补，保留原始私有备份。"""
    lock = path.with_name(path.name + ".news-upgrade.lock")
    try:
        lock_descriptor = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
        with os.fdopen(lock_descriptor, "r+b") as handle:
            info = os.fstat(handle.fileno())
            if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1:
                raise NewsConfigurationError("NEWS_CONFIG_LOCK_INVALID")
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise NewsConfigurationError("NEWS_CONFIG_UPGRADE_BUSY") from error
            original = read_private(path)
            result = plan(original)
            if not result["addKeys"]:
                return result
            extra = additions(original, result["addKeys"])
            # 不重写已有行、引号或注释，只追加本功能缺失项。
            suffix = (b"" if not original.raw or original.raw.endswith(b"\n") else b"\n")
            suffix += b"\n# Added daily news defaults; all new feature switches remain disabled.\n"
            suffix += "".join(f"{key}={value}\n" for key, value in extra.items()).encode("utf-8")
            if len(original.raw) + len(suffix) > MAX_ENV_BYTES:
                raise NewsConfigurationError("NEWS_CONFIG_FILE_TOO_LARGE")
            descriptor, name = tempfile.mkstemp(prefix=".news-env-", dir=path.parent)
            temporary = Path(name)
            try:
                private_write(descriptor, original.raw + suffix, original)
                unchanged(path, original)
                backup = path.with_name(path.name + ".news-backup-" + str(uuid.uuid4()))
                backup_descriptor = os.open(backup, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
                private_write(backup_descriptor, original.raw, original)
                unchanged(path, original)
                os.replace(temporary, path)
                directory = os.open(path.parent, os.O_RDONLY)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
            finally:
                temporary.unlink(missing_ok=True)
            return {**result, "changed": True, "backupCreated": True, "configurationVerified": False}
    except OSError as error:
        raise NewsConfigurationError("NEWS_CONFIG_WRITE_UNCONFIRMED") from error


def main(argv=None) -> int:
    """默认只读并不生成秘密；显式应用后仍需执行运行阶段检查。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=Path("/opt/yuyutian/mytools/config/services.env"))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = apply(args.env_file) if args.apply else plan(read_private(args.env_file))
        print(json.dumps({**result, "applied": args.apply}, sort_keys=True))
        return 0
    except NewsConfigurationError as error:
        print(json.dumps(error.public(), sort_keys=True))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
