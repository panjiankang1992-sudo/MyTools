"""验证真实 Trafilatura 的离线提取与输入限制。"""
import unittest
from unittest.mock import patch
from extract_article import extract_html, MAX_BYTES


class ArticleExtractionTests(unittest.TestCase):
    def test_real_extractor_keeps_article_and_removes_navigation_comments(self):
        paragraphs = ["The city opened a new railway line on Monday connecting the airport with the central station. Services run every fifteen minutes during the day.",
                      "The transport authority published the route map and confirmed that existing travel cards can be used on the new service. Additional stations will open next year."]
        html = "<html><head><title>New railway opens</title></head><body><nav>Menu About Login</nav><article><h1>New railway opens</h1>"
        html += "".join("<p>" + text + "</p>" for text in paragraphs)
        html += "</article><div id='comments'><p>SPECULATIVE_COMMENT</p></div><footer>UNRELATED_FOOTER</footer></body></html>"
        with patch("socket.socket", side_effect=AssertionError("Extraction must not access network")):
            result = extract_html(html, "https://example.com/story")
        self.assertIn("central station", result["text"])
        self.assertIn("travel cards", result["text"])
        self.assertNotIn("SPECULATIVE_COMMENT", result["text"])
        self.assertNotIn("UNRELATED_FOOTER", result["text"])
        self.assertEqual(result["method"], "TRAFILATURA")

    def test_empty_input_and_size_limit(self):
        self.assertEqual(extract_html("<html></html>", "https://example.com")["text"], "")
        with self.assertRaisesRegex(ValueError, "ARTICLE_INPUT_TOO_LARGE"):
            extract_html("x" * (MAX_BYTES + 1), "https://example.com")


if __name__ == "__main__":
    unittest.main()
