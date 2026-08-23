from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import sys
from email.message import Message

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("download_resolve_web_archive", MODULE_PATH)
MODULE = module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def test_parser_prefers_article_and_collects_unique_media():
    page = MODULE.parse_page("""
        <html><head><title>Example</title><script>secret()</script></head>
        <body>outside<article><h1>Heading</h1><p>Article body</p>
        <img src="/a.jpg"><img src="/a.jpg"></article></body></html>
    """, "https://example.com/post")
    assert "Article body" in page.text
    assert "outside" not in page.text
    assert page.media_urls == ["https://example.com/a.jpg"]


def test_resource_plan_contains_generated_text_and_http_media():
    resources = MODULE.build_resources(
        {"maxAssets": 2, "minTextBytes": 1}, "https://example.com/post",
        "<html><head><title>Page</title></head><body><p>Body</p>"
        "<img src='https://cdn.example.com/a.jpg'></body></html>", "text/html")
    assert [value["kind"] for value in resources] == ["TEXT", "HTTP"]
    assert resources[0]["mimeType"] == "text/plain"


def test_url_validation_rejects_private_address():
    def resolver(*_args, **_kwargs):
        return [(None, None, None, None, ("127.0.0.1", 443))]
    try:
        MODULE.validated_url("https://example.com/a", resolver)
        raise AssertionError("private address unexpectedly accepted")
    except ValueError as exception:
        assert "non-public" in str(exception)


def test_fetch_revalidates_every_redirect_target():
    class Response:
        def __init__(self, status, location=None, body=b"<html><body>ok</body></html>"):
            self.status = status
            self.headers = Message()
            if location:
                self.headers["Location"] = location
            self.headers["Content-Type"] = "text/html; charset=utf-8"
            self.body = body

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, limit):
            return self.body[:limit]

    responses = iter([Response(302, "https://cdn.example.com/page"), Response(200)])
    opened = []
    resolved = []

    def opener(request, timeout):
        opened.append((request.full_url, timeout))
        return next(responses)

    def resolver(host, *_args, **_kwargs):
        resolved.append(host)
        return [(None, None, None, None, ("8.8.8.8", 443))]

    final_url, html, _ = MODULE.fetch("https://example.com/start", 1024, opener, resolver)
    assert final_url == "https://cdn.example.com/page"
    assert html == "<html><body>ok</body></html>"
    assert resolved == ["example.com", "cdn.example.com"]
