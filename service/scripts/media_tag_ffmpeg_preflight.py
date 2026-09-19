#!/usr/bin/env python3
"""以 Executor 的受限身份静默验证媒体标签 ffmpeg 运行时。"""

from __future__ import annotations

import json
import os
from pathlib import Path
import pwd
import re
import stat
import subprocess
import tempfile


DEPLOYMENT_ROOT = Path("/opt/yuyutian/mytools")
ENV_PATH = DEPLOYMENT_ROOT / "config" / "services.env"
DEFAULT_FFMPEG_BINARY = "/usr/bin/ffmpeg"
ISOLATED_PATH = "/usr/local/bin:/usr/bin:/bin"
MAX_ENVIRONMENT_BYTES = 1024 * 1024
PREFLIGHT_TIMEOUT_SECONDS = 10
ENVIRONMENT_KEY_PATTERN = re.compile(r"[A-Z][A-Z0-9_]*")


class PreflightError(RuntimeError):
    """标记不可安全继续发布的 ffmpeg 前置条件错误。"""


def require_root_protected_directory(path: Path) -> None:
    """确认目录为 canonical、root 所有且不可由非 root 修改。"""
    try:
        metadata = path.lstat()
        resolved = path.resolve(strict=True)
    except OSError as exception:
        raise PreflightError("protected runtime directory is unavailable") from exception
    if (not path.is_absolute() or resolved != path or not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != 0 or metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH)):
        raise PreflightError("protected runtime directory is invalid")


def load_ffmpeg_setting(path: Path = ENV_PATH) -> str:
    """从私有环境文件中仅解析 ffmpeg 路径，不执行或输出任何配置。"""
    require_root_protected_directory(DEPLOYMENT_ROOT)
    require_root_protected_directory(path.parent)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exception:
        raise PreflightError("deployment environment is unavailable") from exception
    try:
        metadata = os.fstat(descriptor)
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != 0
                or stat.S_IMODE(metadata.st_mode) != 0o600
                or metadata.st_size > MAX_ENVIRONMENT_BYTES):
            raise PreflightError("deployment environment is invalid")
        chunks: list[bytes] = []
        remaining = MAX_ENVIRONMENT_BYTES + 1
        while remaining > 0:
            chunk = os.read(descriptor, min(64 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        payload = b"".join(chunks)
        if len(payload) > MAX_ENVIRONMENT_BYTES:
            raise PreflightError("deployment environment is oversized")
    finally:
        os.close(descriptor)
    try:
        lines = payload.decode("utf-8").splitlines()
    except UnicodeError as exception:
        raise PreflightError("deployment environment is invalid") from exception
    ffmpeg_value: str | None = None
    names: set[str] = set()
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise PreflightError("deployment environment is invalid")
        name, value = line.split("=", 1)
        if ENVIRONMENT_KEY_PATTERN.fullmatch(name) is None or name in names:
            raise PreflightError("deployment environment is invalid")
        names.add(name)
        if name == "FFMPEG_BINARY":
            ffmpeg_value = value
    return ffmpeg_value if ffmpeg_value is not None else DEFAULT_FFMPEG_BINARY


def trusted_ffmpeg_binary(configured: str) -> Path:
    """校验 ffmpeg 为 canonical、root 所有且不可由非 root 修改的绝对文件。"""
    candidate = Path(configured.strip())
    if not configured.strip() or not candidate.is_absolute():
        raise PreflightError("ffmpeg configuration is invalid")
    try:
        resolved = candidate.resolve(strict=True)
        metadata = candidate.lstat()
    except OSError as exception:
        raise PreflightError("ffmpeg runtime is unavailable") from exception
    if (resolved != candidate or not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != 0
            or metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH | stat.S_ISUID | stat.S_ISGID)):
        raise PreflightError("ffmpeg runtime is untrusted")
    for ancestor in candidate.parents:
        require_root_protected_directory(ancestor)
    return candidate


def service_identity() -> tuple[int, int]:
    """解析非 root 的 mytools 服务身份。"""
    try:
        account = pwd.getpwnam("mytools")
    except KeyError as exception:
        raise PreflightError("service identity is unavailable") from exception
    if account.pw_uid <= 0 or account.pw_gid <= 0:
        raise PreflightError("service identity is invalid")
    return account.pw_uid, account.pw_gid


def demote(uid: int, gid: int):
    """构造子进程使用的一次性降权函数。"""
    def apply_identity() -> None:
        os.setgroups([])
        os.setgid(gid)
        os.setuid(uid)

    return apply_identity


def run_preflight(binary: Path, uid: int, gid: int) -> None:
    """用与 Executor 脚本一致的清空环境和服务身份执行最小转码。"""
    if os.geteuid() != 0:
        raise PreflightError("preflight must run as root")
    isolated_environment = {
        "PATH": ISOLATED_PATH,
        "LANG": "C.UTF-8",
        "FFMPEG_BINARY": str(binary),
    }
    with tempfile.TemporaryDirectory(prefix="mytools-ffmpeg-preflight-") as directory:
        work_root = Path(directory)
        os.chown(work_root, uid, gid)
        os.chmod(work_root, 0o700)
        source = work_root / "source.ppm"
        target = work_root / "target.jpg"
        source.write_bytes(b"P6\n2 2\n255\n" + b"\x00\x00\x00" * 4)
        os.chown(source, uid, gid)
        os.chmod(source, 0o600)
        command = [
            str(binary), "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(source), "-an", "-sn", "-dn", "-frames:v", "1",
            "-vf", "scale=2:-2:force_original_aspect_ratio=decrease:out_range=full,format=yuvj420p",
            "-threads", "1", "-q:v", "4", str(target),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                cwd=work_root,
                env=isolated_environment,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=PREFLIGHT_TIMEOUT_SECONDS,
                preexec_fn=demote(uid, gid),
            )
            metadata = target.lstat()
        except (OSError, subprocess.SubprocessError) as exception:
            raise PreflightError("ffmpeg execution preflight failed") from exception
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != uid
                or metadata.st_size <= 2 or metadata.st_size > 1024 * 1024):
            raise PreflightError("ffmpeg execution preflight failed")


def inspect() -> dict[str, bool]:
    """执行完整检查并只返回聚合布尔结果。"""
    try:
        binary = trusted_ffmpeg_binary(load_ffmpeg_setting())
        uid, gid = service_identity()
        run_preflight(binary, uid, gid)
    except Exception:  # noqa: BLE001
        return {"ready": False}
    return {"ready": True}


def main() -> int:
    """输出不包含路径、身份或环境内容的发布判定。"""
    report = inspect()
    print(json.dumps(report, separators=(",", ":")))
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
