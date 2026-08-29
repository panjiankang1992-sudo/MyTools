"""X 登录会话健康检查任务测试。"""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("download_x_auth_health", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Completed:
    """模拟 gallery-dl 执行结果。"""

    def __init__(self, returncode=0, stdout=b"[]"):
        self.returncode = returncode
        self.stdout = stdout


class Response:
    """模拟消息服务响应。"""

    def __init__(self, payload):
        self.payload = json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self, _limit):
        return self.payload


class XAuthHealthTest(unittest.TestCase):
    """验证 Cookie 探测和统一消息告警。"""

    def cookie_file(self):
        """创建不包含真实值的有效 Netscape Cookie 文件。"""
        handle = tempfile.NamedTemporaryFile("w", delete=False)
        handle.write("# Netscape HTTP Cookie File\n")
        handle.write(".x.com\tTRUE\t/\tTRUE\t0\tauth_token\ttest\n")
        handle.write(".x.com\tTRUE\t/\tTRUE\t0\tct0\ttest\n")
        handle.close()
        return Path(handle.name)

    def test_probe_accepts_authenticated_cookie_file(self):
        """有效 Cookie 且探测成功时不抛出异常。"""
        cookie_path = self.cookie_file()
        self.addCleanup(cookie_path.unlink)
        MODULE.probe(cookie_path, "https://x.com/X", lambda *_args, **_kwargs: Completed())

    def test_probe_rejects_auth_required(self):
        """服务返回 AuthRequired 时明确判定会话失效。"""
        cookie_path = self.cookie_file()
        self.addCleanup(cookie_path.unlink)
        runner = lambda *_args, **_kwargs: Completed(stdout=b'[[ -1, {"error":"AuthRequired"} ]]')
        with self.assertRaisesRegex(ValueError, "authentication is required"):
            MODULE.probe(cookie_path, "https://x.com/X", runner)

    def test_alert_replies_to_latest_supported_channel(self):
        """告警通过统一消息服务回复最近使用的支持渠道。"""
        requests = []

        def opener(request, timeout):
            requests.append((request, timeout))
            if request.full_url.endswith("limit=100"):
                return Response({"items": [
                    {"id": "old", "channelType": "QQ", "receivedAt": "2026-01-01T00:00:00Z"},
                    {"id": "new", "channelType": "TELEGRAM", "receivedAt": "2026-01-02T00:00:00Z"}]})
            return Response({"status": "ACCEPTED"})

        old_env = dict(MODULE.os.environ)
        self.addCleanup(lambda: (MODULE.os.environ.clear(), MODULE.os.environ.update(old_env)))
        MODULE.os.environ.update({"MESSAGING_SERVICE_URL": "http://messaging",
                                  "MESSAGING_INTERNAL_TOKEN": "token",
                                  "X_HEALTH_ALERT_OWNER_ID": "1"})
        checked_at = MODULE.datetime(2026, 1, 2, tzinfo=MODULE.timezone.utc)
        self.assertTrue(MODULE.alert("expired", checked_at, opener))
        self.assertIn("/new/replies", requests[1][0].full_url)


if __name__ == "__main__":
    unittest.main()
