"""验证直连站点边界、IPv6 选择和工件增量隔离。"""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch as mock_patch
import zipfile

from apply_gateway_ipv6_delta import ALLOWED_ENTRIES, patch
from generate_gateway_ipv6_config import render
from prepare_ipv6_direct_entry import stable_address
from test_gateway_lan_routes import DSH, GATEWAY, NginxRouteModel, directive


class Ipv6DirectEntryTest(unittest.TestCase):
    """验证生产入口所依赖的安全边界。"""

    def test_renderer_keeps_api_boundaries_and_secure_dsh(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "dsh.conf"
            config.write_text('include /etc/nginx/snippets/mytools-dsh-auth-secret.conf;\nsub_filter_once on;\n')
            result = render("direct6.example.test", 443, config)
            self.assertIn("listen [::]:443 ssl ipv6only=on;", result)
            self.assertIn("ssl_protocols TLSv1.2 TLSv1.3;", result)
            self.assertIn("/internal/dsh-web/connect", result)
            self.assertNotIn("/internal/dsh-web/lan/", result)
            self.assertNotIn("deny all;", result)
            self.assertNotIn("listen 0.0.0.0", result)
            self.assertIn("location ^~ /internal/", result)
            self.assertIn("location ^~ /actuator/", result)
            self.assertIn("error_page 404 = @mytools_gateway_ipv6_legacy_ticket", result)
            self.assertIn("proxy_set_header Authorization $http_authorization;", result)
            self.assertIn("if ($http_sec_fetch_site = cross-site)", result)
            self.assertIn("proxy_set_header Origin https://dsh-app.yuyutian.top;", result)
            self.assertNotIn("@API_PROXY_PARAMETERS@", result)
            self.assertIn("access_log off;", result)
            self.assertIn("gzip_types application/json;", result)
            self.assertIn("proxy_set_header X-Forwarded-Proto https;", result)
            self.assertNotIn("proxy_set_header X-Forwarded-Proto http;", result)
            alternate = render("direct6.example.test", 8443, config)
            self.assertIn("listen [::]:8443 ssl ipv6only=on;", alternate)
            self.assertNotIn("listen [::]:443 ", alternate)
            for rendered, origin in ((result, "https://direct6.example.test"),
                                     (alternate, "https://direct6.example.test:8443")):
                model = NginxRouteModel(rendered)
                for path in ("/api/remote.mux", "/api/session/uploadFileBinary", "/api/fileUploads/upload",
                             "/api/workspaceFiles/readBytes", "/plugins/events"):
                    with self.subTest(path=path, origin=origin):
                        proxy = model.select(path)
                        self.assertEqual([DSH], directive(proxy, "proxy_pass"))
                        self.assertEqual(["/_mytools/authorize"], directive(proxy, "auth_request"))
                        self.assertIn((['if', '($http_origin', '!=', ')'],
                                       [(['set', '$mytools_dsh_expected_origin', origin], [])]), proxy)
                self.assertEqual([GATEWAY], model.request("POST", "/api/app/v1/dsh/web-ticket"))
                self.assertNotIn('"http://$server_addr:23201"', rendered)
            for name in ["evil;return 200", "../cert", "x.example.test\n"]:
                with self.assertRaises(ValueError):
                    render(name, 443, config)

    def test_stable_address_skips_empty_temporary_and_private_entries(self):
        data = [{"ifname": "enp5s0", "addr_info": [{},
            {"local": "fd00::1"}, {"local": "240e::2", "temporary": True},
            {"local": "240e::3", "deprecated": True}, {"local": "240e::4"}]}]
        with mock_patch("prepare_ipv6_direct_entry.subprocess.run") as run:
            run.return_value.stdout = json.dumps(data)
            self.assertEqual(stable_address(), "240e::4")

    def test_delta_preserves_unrelated_classes_and_rejects_unknown_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base, delta, output = [root / name for name in ("base.jar", "delta.zip", "new.jar")]
            with zipfile.ZipFile(base, "w") as archive:
                for name in ALLOWED_ENTRIES:
                    archive.writestr(name, b"old")
                archive.writestr("other-session.class", b"keep-this")
            with zipfile.ZipFile(delta, "w") as archive:
                for name in ALLOWED_ENTRIES:
                    archive.writestr(name, b"new")
            sha = hashlib.sha256(delta.read_bytes()).hexdigest()
            patch(base, delta, output, sha)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(archive.read("other-session.class"), b"keep-this")
            with self.assertRaises(ValueError):
                patch(base, delta, root / "bad.jar", "0" * 64)
            with zipfile.ZipFile(delta, "a") as archive:
                archive.writestr("unrelated.class", b"bad")
            with self.assertRaises(ValueError):
                patch(base, delta, root / "bad.jar", hashlib.sha256(delta.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
