"""Verify the LAN ingress shares Gateway and preserves DSH without exposing secrets."""

from __future__ import annotations

import importlib.util
import json
import re
import stat
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


SPEC = importlib.util.spec_from_file_location(
    "generate_gateway_lan_config", Path(__file__).with_name("generate_gateway_lan_config.py"))
assert SPEC is not None and SPEC.loader is not None
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


class GenerateGatewayLanConfigTest(unittest.TestCase):
    """Cover strict interface addresses, complete forwarding and discovery agreement."""

    def source_config(self, directory: str) -> Path:
        """Build a fixture containing an external key include and HTML bridge."""
        source = Path(directory) / "dsh-lan.conf"
        source.write_text("""server {
    listen 127.0.0.1:3081;
    include /etc/nginx/snippets/mytools-dsh-auth-secret.conf;
    location / {
        sub_filter_types text/html;
        sub_filter_once off;
        sub_filter '</head>' '<script>window.bridge = {a:1};</script></head>';
    }
}
""")
        return source

    def test_accepts_only_rfc1918_ipv4_interfaces(self) -> None:
        for value in ("10.0.1.2", "172.16.4.3", "172.31.255.1", "192.168.8.9"):
            with self.subTest(value=value):
                self.assertEqual(value, generator.normalize_lan_address(value))
        for value in ("127.0.0.1", "169.254.1.2", "172.32.1.1", "8.8.8.8", "::1",
                      "192.168.1.2:23201", "192.168.1.2; return 200", "0.0.0.0"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                generator.normalize_lan_address(value)

    def test_forwards_api_namespace_through_explicit_migration_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "review"
            report = generator.generate("192.168.8.9", output, self.source_config(directory))
            nginx = (output / "mytools-gateway-lan.conf").read_text()
            service = ET.fromstring((output / "mytools-gateway.service").read_text())
            self.assertEqual(0o700, stat.S_IMODE(output.stat().st_mode))
            for path in output.iterdir():
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))

        self.assertIn("listen 192.168.8.9:23201;", nginx)
        self.assertIn("location /api/ {\n        proxy_pass http://127.0.0.1:23200;", nginx)
        self.assertIn("location ^~ /internal/ {\n        return 404;", nginx)
        self.assertIn("location ^~ /actuator/ {\n        return 404;", nginx)
        self.assertIn("deny all;", nginx)
        self.assertIn("access_log off;", nginx)
        self.assertIn("proxy_next_upstream off;", nginx)
        self.assertIn("client_max_body_size 200m;", nginx)
        self.assertIn("proxy_request_buffering off;", nginx)
        self.assertIn("proxy_buffering off;", nginx)
        self.assertEqual("ipv4", service.find("service").attrib["protocol"])
        self.assertEqual("_mytools-gateway._tcp", service.findtext("service/type"))
        self.assertEqual(str(report["lanPort"]), service.findtext("service/port"))
        self.assertFalse(report["installed"])
        self.assertIsNone(re.search(r"@[A-Z_]+@", nginx))

    def test_reuses_key_include_without_reading_key_and_preserves_html_injection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self.source_config(directory)
            output = Path(directory) / "review"
            report = generator.generate("192.168.8.9", output, source)
            nginx = (output / "mytools-gateway-lan.conf").read_text()

        self.assertEqual(2, nginx.count('include "/etc/nginx/snippets/mytools-dsh-auth-secret.conf";'))
        self.assertIn("proxy_pass http://127.0.0.1:23200/internal/dsh-web/lan/connect;", nginx)
        self.assertIn("proxy_pass http://127.0.0.1:23200/internal/dsh-web/lan/authorize;", nginx)
        self.assertIn("location = /_mytools/authorize {\n        internal;", nginx)
        self.assertIn("auth_request /_mytools/authorize;", nginx)
        self.assertIn("proxy_set_header Cookie $mytools_dsh_cookie;", nginx)
        self.assertIn("proxy_set_header Upgrade $http_upgrade;", nginx)
        self.assertIn("proxy_read_timeout 3600s;", nginx)
        self.assertIn("sub_filter '</head>' '<script>window.bridge = {a:1};</script></head>';", nginx)
        self.assertEqual(3, report["reusedDshHtmlFilters"])
        self.assertNotIn("proxy_set_header X-Dsh-Proxy-Key", nginx)

    def test_inline_key_is_private_and_absent_from_manifest(self) -> None:
        fixture_key = "test-only-" + "a" * 40
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "dsh-lan.conf"
            source.write_text('proxy_set_header X-Dsh-Proxy-Key "' + fixture_key + '";')
            output = Path(directory) / "review"
            report = generator.generate("192.168.8.9", output, source)
            self.assertIn(fixture_key, (output / "mytools-gateway-lan.conf").read_text())
            self.assertNotIn(fixture_key, json.dumps(report))
            self.assertNotIn(fixture_key, (output / "manifest.json").read_text())
            self.assertEqual(0o600, stat.S_IMODE((output / "mytools-gateway-lan.conf").stat().st_mode))

    def test_refuses_ambiguous_or_variable_proxy_keys(self) -> None:
        for directives in ('proxy_set_header X-Dsh-Proxy-Key "$unsafe";',
                           'proxy_set_header X-Dsh-Proxy-Key "' + "a" * 40 + '";\n'
                           'proxy_set_header X-Dsh-Proxy-Key "' + "b" * 40 + '";'):
            with self.subTest(directives=directives), tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "dsh-lan.conf"
                source.write_text(directives)
                with self.assertRaises(ValueError):
                    generator.dsh_proxy_material(source)

    def test_refuses_overwriting_existing_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self.source_config(directory)
            output = Path(directory) / "review"
            generator.generate("192.168.8.9", output, source)
            with self.assertRaises(FileExistsError):
                generator.generate("192.168.8.10", output, source)
            self.assertIn("192.168.8.9:23201", (output / "mytools-gateway-lan.conf").read_text())

    def test_binds_only_explicit_interfaces_and_deduplicates_them(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = self.source_config(directory)
            output = Path(directory) / "review"
            report = generator.generate(["192.168.1.7", "192.168.3.159", "192.168.1.7"], output, source)
            nginx = (output / "mytools-gateway-lan.conf").read_text()
        self.assertEqual(2, nginx.count("listen "))
        self.assertIn("listen 192.168.1.7:23201;", nginx)
        self.assertIn("listen 192.168.3.159:23201;", nginx)
        self.assertNotIn("listen 0.0.0.0", nginx)
        self.assertEqual(["192.168.1.7", "192.168.3.159"], report["lanAddresses"])

    def test_invalid_address_creates_no_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "review"
            with self.assertRaises(ValueError):
                generator.generate("127.0.0.1", output, self.source_config(directory))
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
