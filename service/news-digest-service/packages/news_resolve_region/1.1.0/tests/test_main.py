"""节点探测的离线行为验证，夹具不是生产定位证据。"""
from __future__ import annotations

import importlib.util
import io
import ipaddress
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import urllib.request
import uuid

SPEC = importlib.util.spec_from_file_location("news_region_probe", Path(__file__).parents[1] / "scripts/main.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class RegionProbeTest(unittest.TestCase):
    """验证出口身份、精度字段、凭据作用域与错误脱敏。"""

    def setUp(self):
        """创建固定配置和公开测试地址，不访问真实网络。"""
        self.config = {"provider": "MAXMIND_MMDB", "family": "IPV4", "proxy": "", "mmdb": "/test/city.mmdb",
                       "account": "", "license": "", "web_host": "geolite.info", "fingerprint_key": "k" * 32}
        self.address = ipaddress.ip_address("8.8.8.8")
        self.record = {"country": {"iso_code": "CN"}, "subdivisions": [{"iso_code": "SC"}],
                       "city": {"geoname_id": 123, "names": {"en": "Example City"}}, "location": {"accuracy_radius": 20}}
        self.geo = probe.geo_fields(self.record)

    def test_public_address_rejects_private_reserved_mapped_and_wrong_family(self):
        """不能把内网地址或地址族漂移当作执行出口。"""
        for address in ("127.0.0.1", "192.168.1.1", "10.0.0.1", "100.64.0.1", "169.254.169.254", "224.0.0.1", "192.0.2.1", "localhost"):
            with self.subTest(address=address), self.assertRaises(ValueError):
                probe.public_address(address, "IPV4")
        for address in ("::1", "fe80::1%eth0", "::ffff:8.8.8.8", "2001:db8::1", "ff02::1"):
            with self.subTest(address=address), self.assertRaises(ValueError):
                probe.public_address(address, "IPV6")
        self.assertEqual(probe.public_address("2606:4700:4700::1111", "IPV6").version, 6)
        with self.assertRaises(ValueError):
            probe.public_address("8.8.8.8", "IPV6")

    def test_success_is_redacted_and_fingerprint_is_stable_but_scoped(self):
        """指纹稳定用于缓存，完整地址不出现在报告中。"""
        first = self.observe()
        second = self.observe()
        self.assertEqual(first["status"], "RESOLVED")
        self.assertEqual(first["egressFingerprint"], second["egressFingerprint"])
        self.assertNotIn(str(self.address), json.dumps(first))
        self.assertNotIn(self.config["fingerprint_key"], json.dumps(first))
        self.assertNotEqual(first["egressFingerprint"], self.observe(node="another")["egressFingerprint"])
        self.config["proxy"] = "http://proxy.example:3128"
        proxied = self.observe()
        self.assertEqual(proxied["networkMode"], "EXPLICIT_PROXY")
        self.assertNotEqual(first["egressFingerprint"], proxied["egressFingerprint"])

    def test_lookup_failure_retains_verified_egress_but_change_or_unknown_discards_it(self):
        """只有前后两次探测同一出口才允许服务器复用旧地区缓存。"""
        failed = self.observe(lookup=Mock(side_effect=ValueError("8.8.8.8 secret")))
        self.assertEqual(failed["status"], "LOOKUP_FAILED")
        self.assertIsNotNone(failed["egressFingerprint"])
        self.assertIsNone(failed["geo"])
        for response in ([self.address, ipaddress.ip_address("1.1.1.1")], [self.address, OSError("secret")], [OSError("secret")]):
            report = self.observe(egress=Mock(side_effect=response))
            self.assertEqual(report["status"], "EGRESS_UNAVAILABLE")
            self.assertIsNone(report["egressFingerprint"])
            self.assertIsNone(report["geo"])

    def test_geo_parser_does_not_invent_city_or_copy_coordinates(self):
        """只持有国家证据时不会猜出城市，缺失精度保持未知。"""
        country = probe.geo_fields({"country": {"iso_code": "US"}, "location": {"latitude": 1, "longitude": 2}})
        self.assertIsNone(country["cityGeonameId"])
        self.assertIsNone(country["accuracyRadiusKm"])
        self.assertNotIn("latitude", country)
        for record in ({}, {"country": {"iso_code": "US"}, "city": {"geoname_id": True}},
                       {"country": {"iso_code": "US"}, "location": {"accuracy_radius": -1}}):
            with self.assertRaises(ValueError):
                probe.geo_fields(record)

    def test_mmdb_reader_requires_city_database_and_fresh_build_metadata(self):
        """真实读取协议的时间和数据库类型校验不能被文件名替代。"""
        reader = Mock()
        reader.get.return_value = self.record
        reader.metadata.return_value = SimpleNamespace(database_type="GeoLite2-City", build_epoch=1000)
        context = Mock()
        context.__enter__ = Mock(return_value=reader)
        context.__exit__ = Mock(return_value=False)
        with patch.dict("sys.modules", {"maxminddb": SimpleNamespace(open_database=Mock(return_value=context))}), patch.object(probe.time, "time", return_value=2000):
            geo, version = probe.locate(self.address, self.config, None)
            self.assertEqual(geo, self.geo)
            self.assertEqual(version, "GeoLite2-City:1000")
            reader.metadata.return_value = SimpleNamespace(database_type="GeoLite2-Country", build_epoch=1000)
            with self.assertRaises(ValueError):
                probe.locate(self.address, self.config, None)
            reader.metadata.return_value = SimpleNamespace(database_type="GeoLite2-City", build_epoch=-3000000)
            with self.assertRaises(ValueError):
                probe.locate(self.address, self.config, None)

    @unittest.skipUnless(os.getenv("NEWS_REGION_TEST_MMDB"), "Official MMDB fixture is opt-in")
    def test_actual_maxmind_reader_with_official_fixture(self):
        """用官方二进制测试库验证实际读取器，固定库时间不冒充当前定位。"""
        import maxminddb
        self.config["mmdb"] = os.environ["NEWS_REGION_TEST_MMDB"]
        with maxminddb.open_database(self.config["mmdb"]) as reader:
            epoch = reader.metadata().build_epoch
        with patch.object(probe.time, "time", return_value=epoch + 1):
            geo, version = probe.locate(ipaddress.ip_address("81.2.69.160"), self.config, None)
        self.assertEqual(geo["countryCode"], "GB")
        self.assertEqual(geo["cityGeonameId"], 2643743)
        self.assertTrue(version.startswith("GeoIP2-City:"))

    def test_web_lookup_uses_fixed_https_host_and_checks_returned_ip(self):
        """供应商鉴权仅发往官方主机，结果必须属于已探测出口。"""
        self.config.update(provider="MAXMIND_WEB", account="123", license="test-license")
        record = {**self.record, "traits": {"ip_address": str(self.address)}}
        with patch.object(probe, "request_json", return_value=record) as request:
            geo, version = probe.locate(self.address, self.config, None)
            self.assertEqual(geo, self.geo)
            self.assertEqual(version, "geolite.info:v2.1")
            self.assertEqual(request.call_args.args[1], "https://geolite.info/geoip/v2.1/city/8.8.8.8")
            record["traits"]["ip_address"] = "1.1.1.1"
            with self.assertRaises(ValueError):
                probe.locate(self.address, self.config, None)
            self.config["web_host"] = "example.com"
            with self.assertRaises(ValueError):
                probe.locate(self.address, self.config, None)

    def test_explicit_proxy_does_not_honor_environment_bypass(self):
        """显式代理不能被 NO_PROXY 意外绕过，也不接受代理内嵌凭据。"""
        with patch.dict(os.environ, {"no_proxy": "*", "https_proxy": "http://unwanted:3128"}):
            direct = probe.network_client()
            self.assertFalse(any(getattr(handler, "proxies", None) for handler in direct.handlers))
            request = urllib.request.Request("https://api.ipify.org?format=json")
            probe.ExplicitProxy({"https": "http://proxy.example:3128"}).proxy_open(request, "http://proxy.example:3128", "https")
            self.assertEqual(request.host, "proxy.example:3128")
            self.assertEqual(request._tunnel_host, "api.ipify.org")
        for proxy in ("http://user:password@proxy.example", "http://proxy.example/path", "https://proxy.example", "file:///tmp"):
            with self.assertRaises(ValueError):
                probe.network_client(proxy)

    def test_redirect_size_and_nonobject_json_are_rejected(self):
        """限制响应并拒绝跨站跳转，节点凭据不跟随重定向。"""
        with self.assertRaises(ValueError):
            probe.NoRedirect().redirect_request(None, None, 302, "", {}, "https://elsewhere")
        for data in (b"x" * (probe.MAX_BYTES + 1), b"[]"):
            response = io.BytesIO(data)
            response.status = 200
            client = Mock()
            client.open.return_value = response
            with self.assertRaises(ValueError):
                probe.request_json(client, "https://api.ipify.org")

    def test_job_submission_uses_current_lease_and_minimal_receipt(self):
        """固定任务、执行和租约绑定到受控回传接口。"""
        report = self.observe()
        job, lease = str(uuid.uuid4()), str(uuid.uuid4())
        receipt = {"jobId": job, "observationId": report["id"], "status": "STORED", "observationStatus": "RESOLVED"}
        sender = Mock(return_value=receipt)
        result = probe.job_request(job, report["id"], lease, "http://127.0.0.1:23350", "t" * 32, "observation", report, sender=sender)
        self.assertEqual(result, receipt)
        self.assertEqual(sender.call_args.kwargs["headers"]["X-Task-Lease-Token"], lease)
        self.assertEqual(sender.call_args.kwargs["headers"]["X-Task-Execution-Id"], report["id"])
        for url in ("http://remote.example", "https://user:pw@example.com", "https://example.com?other=x"):
            with self.assertRaises(ValueError):
                probe.job_request(job, report["id"], lease, url, "t" * 32, "observation", report, sender=sender)

    def test_receipt_rejects_other_jobs_and_sensitive_extra_fields(self):
        """回执不能换成另一作业或夹带不需要保存的信息。"""
        job, execution, lease = [str(uuid.uuid4()) for _ in range(3)]
        valid = {"jobId": job, "observationId": execution, "status": "STORED", "observationStatus": "RESOLVED"}
        for result in [{**valid, "jobId": str(uuid.uuid4())}, {**valid, "egressFingerprint": "a" * 64}, {**valid, "status": "READY"}]:
            with self.assertRaises(ValueError):
                probe.job_request(job, execution, lease, "http://127.0.0.1:23350", "t" * 32, "receipt", sender=Mock(return_value={"receipt": result}))

    def environment(self, directory):
        """生成离线工作目录和非真实租约。"""
        execution, job = str(uuid.uuid4()), str(uuid.uuid4())
        context = Path(directory) / "context.json"
        context.write_text(json.dumps({"taskName": "news_resolve_region", "stepName": "resolve", "executionId": execution, "parameters": {"jobId": job}}))
        lease = Path(directory) / "lease"
        lease.write_text(str(uuid.uuid4()))
        env = {"TASK_EXECUTOR_NODE_AFFINITY": "node", "TASK_EXECUTION_ID": execution,
               "TASK_CONTEXT_FILE": str(context), "TASK_LEASE_TOKEN_FILE": str(lease),
               "TASK_WORK_DIR": directory, "TASK_RESULT_FILE": directory + "/result.json",
               "NEWS_REGION_FINGERPRINT_KEY": "k" * 32, "NEWS_JOB_TOKEN": "t" * 32}
        receipt = {"jobId": job, "observationId": execution, "status": "STORED", "observationStatus": "RESOLVED"}
        return env, receipt

    def test_task_retry_reuses_journal_without_exposing_failure(self):
        """回传失败重试先查询结果，未提交时重放原始报告。"""
        with tempfile.TemporaryDirectory() as directory:
            env, receipt = self.environment(directory)
            report = self.observe(report_id=env["TASK_EXECUTION_ID"])
            with patch.dict(os.environ, env), patch.object(probe, "observe", return_value=report) as observe, \
                    patch.object(probe, "job_request", side_effect=[None, OSError("secret 8.8.8.8"), None, receipt]), \
                    patch("sys.stderr", new_callable=io.StringIO) as stderr:
                self.assertEqual(probe.main(), 1)
                self.assertNotIn("secret", stderr.getvalue())
                self.assertNotIn("8.8.8.8", stderr.getvalue())
                self.assertEqual(probe.main(), 0)
                observe.assert_called_once()
                self.assertEqual((Path(directory) / "region-observation.json").stat().st_mode & 0o777, 0o600)

    def test_prior_receipt_avoids_probe_on_new_execution(self):
        """调度器重试不会对已持久化的作业再创建新的地区观测。"""
        with tempfile.TemporaryDirectory() as directory:
            env, receipt = self.environment(directory)
            receipt["observationId"] = str(uuid.uuid4())
            with patch.dict(os.environ, env), patch.object(probe, "observe") as observe, patch.object(probe, "job_request", return_value=receipt):
                self.assertEqual(probe.main(), 0)
                observe.assert_not_called()
                self.assertEqual(json.loads(Path(env["TASK_RESULT_FILE"]).read_text()), receipt)

    def test_wrong_context_does_not_probe_or_call_news(self):
        """只能执行冻结的地区任务，不接受参数中的所有者或节点覆盖。"""
        with tempfile.TemporaryDirectory() as directory:
            env, receipt = self.environment(directory)
            path = Path(env["TASK_CONTEXT_FILE"])
            context = json.loads(path.read_text()); context["parameters"]["nodeId"] = "other"; path.write_text(json.dumps(context))
            with patch.dict(os.environ, env), patch.object(probe, "observe") as observe, patch.object(probe, "job_request") as request, patch("sys.stderr", new_callable=io.StringIO):
                self.assertEqual(probe.main(), 1)
                observe.assert_not_called(); request.assert_not_called()

    def observe(self, *, node="node", report_id=None, egress=None, lookup=None):
        """注入离线出口与定位响应，不建立网络连接。"""
        return probe.observe(node, report_id or str(uuid.uuid4()), self.config,
                             egress=egress or Mock(return_value=self.address), lookup=lookup or Mock(return_value=(self.geo, "fixture-only")))


if __name__ == "__main__":
    unittest.main()
