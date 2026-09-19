"""媒体标签 ffmpeg 发布前检查测试。"""

import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("media_tag_ffmpeg_preflight.py")
SPEC = importlib.util.spec_from_file_location("media_tag_ffmpeg_preflight", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MediaTagFfmpegPreflightTest(unittest.TestCase):
    """验证检查器不会泄露配置且严格复现 Executor 运行身份。"""

    def test_rejects_relative_binary(self):
        """相对路径必须在运行进程前失败。"""
        with self.assertRaises(MODULE.PreflightError):
            MODULE.trusted_ffmpeg_binary("ffmpeg")

    def test_accepts_trusted_canonical_binary(self):
        """root 保护的 canonical 文件可以进入执行检查。"""
        candidate = Path("/usr/bin/ffmpeg")
        file_metadata = os.stat_result((stat.S_IFREG | 0o755, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        with patch.object(MODULE.Path, "resolve", return_value=candidate), \
                patch.object(MODULE.Path, "lstat", return_value=file_metadata), \
                patch.object(MODULE, "require_root_protected_directory"):
            self.assertEqual(candidate, MODULE.trusted_ffmpeg_binary(str(candidate)))

    def test_runs_silent_bounded_transcode_with_isolated_environment(self):
        """子进程必须降权、清空环境并关闭三个标准流。"""
        captured = {}
        service_uid = os.getuid()
        service_gid = os.getgid()

        def execute(command, **kwargs):
            captured["command"] = command
            captured.update(kwargs)
            Path(command[-1]).write_bytes(b"jpeg")
            return subprocess.CompletedProcess(command, 0)

        with patch.object(MODULE.os, "geteuid", return_value=0), \
                patch.object(MODULE.os, "chown"), \
                patch.object(MODULE.subprocess, "run", side_effect=execute):
            MODULE.run_preflight(Path("/usr/bin/ffmpeg"), service_uid, service_gid)

        self.assertEqual({
            "PATH": MODULE.ISOLATED_PATH,
            "LANG": "C.UTF-8",
            "FFMPEG_BINARY": "/usr/bin/ffmpeg",
        }, captured["env"])
        self.assertIs(subprocess.DEVNULL, captured["stdin"])
        self.assertIs(subprocess.DEVNULL, captured["stdout"])
        self.assertIs(subprocess.DEVNULL, captured["stderr"])
        self.assertEqual(MODULE.PREFLIGHT_TIMEOUT_SECONDS, captured["timeout"])
        with patch.object(MODULE.os, "setgroups") as setgroups, \
                patch.object(MODULE.os, "setgid") as setgid, \
                patch.object(MODULE.os, "setuid") as setuid:
            captured["preexec_fn"]()
        setgroups.assert_called_once_with([])
        setgid.assert_called_once_with(service_gid)
        setuid.assert_called_once_with(service_uid)

    def test_inspect_returns_only_aggregate_boolean(self):
        """失败输出不得包含环境值、路径或账号信息。"""
        with patch.object(MODULE, "load_ffmpeg_setting",
                          side_effect=MODULE.PreflightError("sensitive detail")):
            report = MODULE.inspect()
        self.assertEqual({"ready": False}, report)
        self.assertNotIn("sensitive", json.dumps(report))

    def test_defaults_missing_setting_without_exposing_other_values(self):
        """未配置时应与 Executor 默认值保持一致。"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "services.env"
            path.write_text("PRIVATE_TOKEN=do-not-return\n", encoding="utf-8")
            os.chmod(path, 0o600)
            size = path.stat().st_size
            trusted_metadata = os.stat_result((
                stat.S_IFREG | 0o600, 0, 0, 1, 0, 0, size, 0, 0, 0,
            ))
            with patch.object(MODULE, "require_root_protected_directory"), \
                    patch.object(MODULE.os, "fstat", return_value=trusted_metadata):
                self.assertEqual(MODULE.DEFAULT_FFMPEG_BINARY,
                                 MODULE.load_ffmpeg_setting(path))


if __name__ == "__main__":
    unittest.main()
