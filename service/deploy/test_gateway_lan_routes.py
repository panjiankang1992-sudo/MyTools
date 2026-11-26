"""Model Nginx matching against generated configuration, including method and 404 dispatch."""

from __future__ import annotations

import importlib.util
import re
import shlex
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location(
    "generate_gateway_lan_config", Path(__file__).with_name("generate_gateway_lan_config.py"))
assert SPEC is not None and SPEC.loader is not None
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)
GATEWAY = "http://127.0.0.1:23200"
LEGACY = "http://127.0.0.1:23110"
DSH = "http://127.0.0.1:3080"


def parse_nginx(source: str) -> list:
    """Parse actual rendered directives with quote-aware tokenization."""
    lexer = shlex.shlex(source, posix=True, punctuation_chars="{};")
    lexer.whitespace_split = True
    tokens: list[str] = []
    for token in lexer:
        tokens.extend(list(token) if token and set(token) <= set("{};") else [token])
    position = 0

    def block() -> list:
        nonlocal position
        nodes = []
        head: list[str] = []
        while position < len(tokens):
            token = tokens[position]
            position += 1
            if token == ";":
                nodes.append((head, []))
                head = []
            elif token == "{":
                nodes.append((head, block()))
                head = []
            elif token == "}":
                return nodes
            else:
                head.append(token)
        return nodes

    return block()


def directive(nodes: list, name: str) -> list[str]:
    """Read a directive from its immediate configuration context."""
    return next((head[1:] for head, _ in nodes if head and head[0] == name), [])


class NginxRouteModel:
    """Apply exact, longest-prefix, regex-order and named-location rules to actual output."""

    def __init__(self, source: str):
        self.nodes = parse_nginx(source)
        server = next(body for head, body in self.nodes if head == ["server"])
        self.locations = [(head[1:], body) for head, body in server if head and head[0] == "location"]

    def select(self, uri: str) -> list:
        path = uri.split("?", 1)[0]
        for head, body in self.locations:
            if head == ["=", path]:
                return body
        prefixes = [(head, body) for head, body in self.locations
                    if head[0] not in ("=", "~", "~*") and not head[0].startswith("@")
                    and path.startswith(head[-1])]
        prefix = max(prefixes, key=lambda value: len(value[0][-1]), default=None)
        if prefix is not None and prefix[0][0] == "^~":
            return prefix[1]
        for head, body in self.locations:
            if head[0] in ("~", "~*") and re.search(
                    head[1], path, re.IGNORECASE if head[0] == "~*" else 0):
                return body
        if prefix is None:
            raise AssertionError("No matching location for " + uri)
        return prefix[1]

    def upstream(self, body: list, method: str) -> str | None:
        for head, children in body:
            if head and head[0] == "if":
                condition = " ".join(head[1:]).strip("()")
                variable, operator, pattern = condition.split(" ", 2)
                if variable != "$request_method":
                    raise AssertionError("Unsupported route condition")
                match = re.search(pattern, method) is not None
                if match if operator == "~" else not match:
                    if directive(children, "return") == ["404"]:
                        return None
                    raise AssertionError("Unexpected method guard")
        value = directive(body, "proxy_pass")[0]
        if "$" in value:
            variable = value.removeprefix("http://")
            mapping = next(children for head, children in self.nodes
                           if head[:2] == ["map", "$request_method"] and head[2] == variable)
            target = directive(mapping, method) or directive(mapping, "default")
            value = "http://" + target[0]
        return value

    def request(self, method: str, uri: str, upstream_status: int = 200) -> list[str]:
        body = self.select(uri)
        first = self.upstream(body, method)
        targets = [first] if first is not None else []
        fallback = directive(body, "error_page")
        if upstream_status == 404 and directive(body, "proxy_intercept_errors") == ["on"] and fallback:
            if fallback[:2] != ["404", "="]:
                raise AssertionError("Unexpected fallback status")
            named = next(children for head, children in self.locations if head == [fallback[2]])
            second = self.upstream(named, method)
            if second is not None:
                targets.append(second)
        return targets


class GatewayLanRoutesTest(unittest.TestCase):
    """Use real App paths to verify routing and prevent broader legacy fallbacks."""

    @classmethod
    def setUpClass(cls) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "dsh.conf"
            source.write_text("include /etc/nginx/snippets/mytools-dsh-auth-secret.conf;")
            output = Path(directory) / "rendered"
            generator.generate("192.168.8.9", output, source)
            cls.nginx = (output / "mytools-gateway-lan.conf").read_text()
        cls.model = NginxRouteModel(cls.nginx)

    def test_existing_app_routes_keep_their_legacy_service(self) -> None:
        paths = ["/api/cloud", "/api/cloud/accounts/one", "/api/ebooks", "/api/ebooks/import",
                 "/api/ebooks/files/book.epub", "/api/localfiles", "/api/localfiles/upload",
                 "/api/app/v1/local-media/sources", "/api/public/feedback",
                 "/api/app/v1/files/download?path=book.epub", "/api/app/v1/reader/data",
                 "/api/app/v1/reader/data/summary", "/api/app/v1/reader/source-discovery",
                 "/api/app/v1/reader/source-discovery/1d72276d-0f5b-42a0-a9eb-5b2d33f34045",
                 "/api/app/v1/media/thumbnail?path=photo.jpg",
                 "/api/app/v1/media/tickets/" + "a" * 32 + "/metrics"]
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual([LEGACY], self.model.request("GET", path))

    def test_new_routes_and_similar_unknown_paths_stay_on_gateway_even_on_404(self) -> None:
        paths = ["/api/app/v1/connectivity/bootstrap", "/api/public/connectivity/challenge",
                 "/api/app/v1/identity/me", "/api/app/v1/identity/refresh",
                 "/api/app/v1/reader/shelves", "/api/app/v1/reader/source-discoveries/task",
                 "/api/app/v1/reader/source-discovery/task/extra",
                 "/api/app/v1/media/catalog", "/api/app/v1/media/tags",
                 "/api/app/v1/audiobook-playback/tickets/" + "b" * 32,
                 "/api/app/v1/drive/items", "/api/app/v1/rss/feeds",
                 "/api/app/v1/pdf/history", "/api/app/v1/image-generation/tasks",
                 "/api/cloudish", "/api/ebooksmith", "/api/localfiles2",
                 "/api/public/feedback/unknown", "/api/app/v1/reader/data/unknown",
                 "/api/app/v1/media/thumbnail/unknown", "/api/unknown",
                 "/api/remote.mux/extra", "/api/remote.mux-other", "/api/workspaceUnknown/list",
                 "/api/workspace/list/extra", "/api/file/extra", "/api/settings",
                 "/api/fileUploadsExtra/upload", "/api/fileUploads/upload/extra",
                 "/api/workspaceFiles/list/extra", "/api/changes.summary/extra",
                 "/api/v0/users/get_user_summary", "/api/experimental-inspector/bootstrap",
                 "/api/app/v1/dsh/web-ticket"]
        for method in ("GET", "POST", "PUT", "DELETE"):
            for path in paths:
                with self.subTest(method=method, path=path):
                    self.assertEqual([GATEWAY], self.model.request(method, path, 404))

    def test_ticket_creation_dispatches_only_post_to_legacy(self) -> None:
        for method in ("GET", "HEAD", "PUT", "DELETE", "OPTIONS"):
            with self.subTest(method=method):
                self.assertEqual([GATEWAY], self.model.request(method, "/api/app/v1/media/tickets", 404))
        self.assertEqual([LEGACY], self.model.request("POST", "/api/app/v1/media/tickets", 404))

    def test_ticket_reads_fallback_only_on_gateway_404(self) -> None:
        uri = "/api/app/v1/media/tickets/" + "c" * 32 + "?position=0"
        for method in ("GET", "HEAD"):
            with self.subTest(method=method):
                self.assertEqual([GATEWAY, LEGACY], self.model.request(method, uri, 404))
                for status in (200, 206, 401, 403, 500, 502, 504):
                    self.assertEqual([GATEWAY], self.model.request(method, uri, status))
        for method in ("POST", "PUT", "DELETE", "PATCH"):
            with self.subTest(method=method):
                self.assertEqual([GATEWAY], self.model.request(method, uri, 404))

    def test_every_api_proxy_preserves_auth_and_disables_cache_and_replays(self) -> None:
        for head, body in self.model.locations:
            if head[-1].startswith("/api/") or head[0] in ("~", "@mytools_gateway_lan_legacy_ticket"):
                if not directive(body, "proxy_pass"):
                    continue
                with self.subTest(location=head):
                    if directive(body, "proxy_pass") == [DSH]:
                        self.assertEqual(["/_mytools/authorize"], directive(body, "auth_request"))
                    else:
                        self.assertIn((["proxy_set_header", "Authorization", "$http_authorization"], []), body)
                    self.assertEqual(["off"], directive(body, "proxy_cache"))
                    self.assertEqual(["off"], directive(body, "proxy_next_upstream"))
                    self.assertEqual(["off"], directive(body, "proxy_buffering"))
                    self.assertEqual(["off"], directive(body, "proxy_request_buffering"))

    def test_dsh_stream_uses_authenticated_dsh_upstream_instead_of_api_gateway(self) -> None:
        """实时通道保留网页认证与升级头，近似路径仍属于业务网关。"""
        body = self.model.select("/api/remote.mux?revision=test")
        self.assertEqual([DSH], directive(body, "proxy_pass"))
        self.assertEqual(["/_mytools/authorize"], directive(body, "auth_request"))
        self.assertEqual(["1.1"], directive(body, "proxy_http_version"))
        self.assertEqual(["3600s"], directive(body, "proxy_read_timeout"))
        for name, value in (("Cookie", "$mytools_dsh_cookie"), ("Upgrade", "$http_upgrade"),
                            ("Connection", "$mytools_gateway_lan_connection"),
                            ("Host", "dsh-app.yuyutian.top"), ("Origin", "https://dsh-app.yuyutian.top")):
            self.assertIn((["proxy_set_header", name, value], []), body)
        self.assertEqual(["Set-Cookie"], directive(body, "proxy_hide_header"))
        self.assertIn('set $mytools_dsh_expected_origin "http://$server_addr:23201";', self.nginx)
        self.assertIn('if ($http_origin != $mytools_dsh_expected_origin) { return 403; }', self.nginx)
        for path in ("/api/workspace/list", "/api/session/get", "/api/settings/get",
                     "/api/terminal/create", "/api/file?path=sample.txt", "/api/$events/result"):
            with self.subTest(path=path):
                self.assertEqual(body, self.model.select(path))

    def test_dsh_attachment_and_feature_requests_use_authenticated_streaming_proxy(self) -> None:
        paths = ("/api/session/uploadFileBinary?sessionId=sample&name=image.png", "/api/fileUploads/upload",
                 "/api/workspaceFiles/readBytes", "/api/session.export", "/api/officeToPdf/render",
                 "/api/changes.summary", "/api/changes.diff", "/api/changes.open",
                 "/api/present.host", "/api/present.open", "/api/agentPresets/list", "/api/commands/list",
                 "/api/goals/get", "/api/llm/listProviders", "/api/messageFeedback/list",
                 "/api/permissionPresets/catalog", "/api/pluginInventory/list", "/api/pluginManager/listPlugins",
                 "/api/pluginMarket/search", "/api/pluginRegistryProbe/fastest", "/api/productAnalytics/enabled",
                 "/api/remoteAccess/follow", "/api/schedule/list", "/api/sessionFeedback/record",
                 "/api/sessionReferenceResolver/candidates", "/api/subagents/prompt", "/api/userQuestions/answer")
        stream = self.model.select("/api/remote.mux")
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(stream, self.model.select(path))
        self.assertEqual(["300m"], directive(stream, "client_max_body_size"))
        self.assertEqual([], directive(self.model.select("/api/app/v1/dsh/web-ticket"), "client_max_body_size"))

    def test_dsh_plugins_and_mobile_assets_use_the_lan_origin(self) -> None:
        page = self.model.select("/")
        for path in ("/plugins/events", "/plugins/fixture.js", "/open-in-app/apps"):
            self.assertEqual(page, self.model.select(path))
        self.assertEqual([DSH], directive(page, "proxy_pass"))
        self.assertEqual(["/_mytools/authorize"], directive(page, "auth_request"))
        self.assertEqual(["Set-Cookie"], directive(page, "proxy_hide_header"))
        self.assertEqual(["off"], directive(page, "proxy_buffering"))
        self.assertIn((['if', '($http_origin', '!=', '$mytools_dsh_expected_origin)'],
                       [(['return', '403'], [])]), page)
        for extension in ("css", "js"):
            style = self.model.select(f"/_mytools/mobile.{extension}?v=test")
            self.assertEqual([f"/var/www/mytools-dsh-mobile/mobile.{extension}"], directive(style, "alias"))
            self.assertEqual([], directive(style, "proxy_pass"))

    def test_dsh_session_status_uses_only_browser_session_authentication(self) -> None:
        """状态探针只验证当前网页会话，不请求 DSH 业务接口。"""
        public_source = (Path(__file__).resolve().parents[2] / 'deploy/nginx/dsh-lan.conf').read_text()
        for source, auth in ((self.nginx, '/_mytools/authorize'), (public_source, '/_mytools/auth')):
            with self.subTest(auth=auth):
                body = NginxRouteModel(source).select('/_mytools/session-status')
                self.assertEqual([auth], directive(body, 'auth_request'))
                self.assertEqual(['Cache-Control', 'no-store', 'always'], directive(body, 'add_header'))
                self.assertEqual(['/.__mytools_dsh_session_status', '=204'], directive(body, 'try_files'))
                self.assertEqual([], directive(body, 'proxy_pass'))
                self.assertIn((['if', '($request_method', '!=', 'GET)'], [(['return', '405'], [])]), body)
                self.assertIn((['if', '($http_sec_fetch_site', '=', 'cross-site)'], [(['return', '403'], [])]), body)

    def test_dsh_prompt_auth_subrequest_accepts_parent_content_length_without_forwarding_body(self) -> None:
        """大附件发送不能被继承较小请求上限的鉴权子请求提前拒绝。"""
        public_source = (Path(__file__).resolve().parents[2] / 'deploy/nginx/dsh-lan.conf').read_text()
        for source in (self.nginx, public_source):
            model = NginxRouteModel(source)
            prompt = model.select('/api/session/prompt')
            auth = model.select(directive(prompt, 'auth_request')[0])
            with self.subTest(auth=directive(auth, 'proxy_pass')):
                self.assertEqual(['300m'], directive(prompt, 'client_max_body_size'))
                self.assertEqual(directive(prompt, 'client_max_body_size'), directive(auth, 'client_max_body_size'))
                self.assertEqual(['off'], directive(auth, 'proxy_pass_request_body'))
                self.assertIn((['proxy_set_header', 'Content-Length', ''], []), auth)

    def test_dsh_upload_relay_is_authenticated_and_public_only(self) -> None:
        """公网中继逐次鉴权，局域网和 IPv6 模板仍使用原有直连链路。"""
        source = Path(__file__).resolve().parents[2] / 'deploy/nginx/dsh-lan.conf'
        public = NginxRouteModel(source.read_text())
        route = public.select('/_mytools/upload/part')
        for endpoint in ('status', 'part', 'commit'):
            self.assertEqual(route, public.select('/_mytools/upload/' + endpoint))
        self.assertEqual(['/_mytools/auth'], directive(route, 'auth_request'))
        self.assertEqual(['http://unix:/run/mytools-dsh-upload.sock'], directive(route, 'proxy_pass'))
        self.assertEqual(['256k'], directive(route, 'client_max_body_size'))
        self.assertEqual(['on'], directive(route, 'proxy_request_buffering'))
        self.assertEqual(['off'], directive(route, 'proxy_pass_request_headers'))
        self.assertEqual(['off'], directive(route, 'proxy_next_upstream'))
        self.assertEqual(['off'], directive(route, 'access_log'))
        self.assertIn((['if', '($http_sec_fetch_site', '=', 'cross-site)'], [(['return', '403'], [])]), route)
        self.assertEqual(['$mytools_dsh_upload_upstream', '$upstream_http_x_dsh_upstream_cookie'],
                         directive(route, 'auth_request_set'))
        self.assertIn((['proxy_set_header', 'X-MyTools-Dsh-Upstream', '$mytools_dsh_upload_upstream'], []), route)
        self.assertIn((['proxy_set_header', 'Cookie', '$http_cookie'], []), route)
        script = public.select('/_mytools/upload.js?v=20261122b')
        self.assertEqual(['/var/www/mytools-dsh-mobile/upload.js'], directive(script, 'alias'))
        self.assertIn((['include', '/etc/nginx/snippets/mytools-dsh-upload-head.conf'], []), public.select('/'))
        _, direct_filters = generator.dsh_proxy_material(source)
        self.assertTrue(any('mobile.js' in item for item in direct_filters))
        self.assertFalse(any('upload.js' in item for item in direct_filters))
        self.assertEqual(self.model.select('/'), self.model.select('/_mytools/upload/part'))


if __name__ == "__main__":
    unittest.main()
