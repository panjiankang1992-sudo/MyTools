package com.yuyutian.mytools.feed;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class FeedCoreTest {
    private final FeedParser parser = new FeedParser();
    private static final String URL = "https://example.com/feed.xml";

    @Test void rssOpaqueGuidPreservesIdentityAndHtmlAcrossRevision() {
        var first = parse(rss("<guid isPermaLink='false'>urn:item:1</guid><title>First</title><description><![CDATA[<p>Text <strong>bold</strong><img src='/photo.jpg' onerror='attack()'/><script>attack()</script></p>]]></description>"));
        var revised = parse(rss("<guid isPermaLink='false'>urn:item:1</guid><title>Changed</title><description>New</description>"));
        var entry = first.entries().getFirst();
        assertThat(entry.identityType()).isEqualTo("NATIVE");
        assertThat(entry.identityHash()).isEqualTo(revised.entries().getFirst().identityHash());
        assertThat(entry.contentHash()).isNotEqualTo(revised.entries().getFirst().contentHash());
        assertThat(entry.contentHtml()).contains("<strong>bold</strong>", "https://example.com/photo.jpg").doesNotContain("script", "onerror");
        assertThat(entry.url()).isEmpty();
        assertThat(entry.publishedAt()).isNull();
    }

    @Test void atomXhtmlXmlBaseAndPublishedRemainDistinct() {
        var feed = parse("""
                <feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://example.com/news/">
                  <title>Feed</title><entry xml:base="daily/"><id>urn:one</id><title>Title</title>
                  <link href="one"/><updated>2026-11-01T01:00:00Z</updated>
                  <content type="xhtml"><x:div xmlns:x="http://www.w3.org/1999/xhtml"><x:p>Keep <x:em>format</x:em><x:img src="picture.png"/></x:p></x:div></content>
                  </entry></feed>
                """);
        var entry = feed.entries().getFirst();
        assertThat(entry.url()).isEqualTo("https://example.com/news/daily/one");
        assertThat(entry.contentHtml()).contains("<em>format</em>", "https://example.com/news/daily/picture.png");
        assertThat(entry.publishedAt()).isNull();
        assertThat(entry.updatedAt()).isNotNull();
    }

    @Test void atomExternalContentDoesNotFetchAndTextIsEscaped() {
        var feed = parse("<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>a</id><title>A</title><content src='http://127.0.0.1/private'/><summary type='text'>&lt;b&gt;literal&lt;/b&gt;</summary></entry></feed>");
        assertThat(feed.entries().getFirst().contentHtml()).contains("&lt;b&gt;literal&lt;/b&gt;");
    }

    @Test void missingTitleUsesContentAndDifferentIdsDoNotCollapseSharedLink() {
        var feed = parse("<rss version='2.0'><channel><item><guid>a</guid><link>https://example.com/shared</link><description>Readable</description></item><item><guid>b</guid><link>https://example.com/shared</link><description>Also readable</description></item></channel></rss>");
        assertThat(feed.entries()).hasSize(2);
        assertThat(feed.entries().getFirst().title()).isEqualTo("Readable");
    }

    @Test void namespaceSpoofDoesNotReplaceRssFields() {
        var feed = parse(rss("<title>Real</title><evil:guid xmlns:evil='urn:evil'>injected</evil:guid><link>https://example.com/a</link><description>Text</description>"));
        assertThat(feed.entries().getFirst().identityType()).isEqualTo("URL");
    }

    @Test void canonicalKeepsSignedQueryAndPathCase() {
        assertThat(FeedParser.canonical("https://EXAMPLE.COM:443/A%2fb?token=a%2Fb&z=2&a=1#part"))
                .isEqualTo("https://example.com/A%2fb?token=a%2Fb&z=2&a=1");
    }

    @Test void rejectsXxeDepthAndOversizedFeeds() {
        assertThatThrownBy(() -> parse("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss version='2.0'><channel>&x;</channel></rss>")) .isInstanceOf(FeedException.class);
        assertThatThrownBy(() -> SafeXml.parse(("<a>".repeat(70) + "</a>".repeat(70)).getBytes(StandardCharsets.UTF_8))).isInstanceOf(FeedException.class);
        assertThatThrownBy(() -> parser.parse(new byte[SafeXml.MAX_BYTES + 1], URL)).isInstanceOf(FeedException.class);
        assertThatThrownBy(() -> parse("<rss version='2.0'><channel>" + "<item><title>A</title></item>".repeat(2001) + "</channel></rss>")).isInstanceOf(FeedException.class);
    }

    @Test void fullWeekOfIndividualNewsKeepsEveryDetail() {
        StringBuilder xml = new StringBuilder("<rss version='2.0' xmlns:content='http://purl.org/rss/1.0/modules/content/'><channel>");
        for (int i = 0; i < 1400; i++) {
            xml.append("<item><guid>urn:news:").append(i).append("</guid><title>News ").append(i)
                    .append("</title><description>Brief</description><content:encoded><![CDATA[<p>Detailed facts</p><ul><li>Verified point</li></ul><img src='https://example.com/news.png'>]]></content:encoded></item>");
        }
        var feed = parse(xml.append("</channel></rss>").toString());
        assertThat(feed.entries()).hasSize(1400);
        assertThat(feed.entries()).allSatisfy(entry -> assertThat(entry.contentHtml())
                .contains("Detailed facts", "<ul>", "https://example.com/news.png"));
        assertThat(feed.entries().stream().map(FeedModels.Entry::identityHash).distinct()).hasSize(1400);
    }

    @Test void sanitizesDangerousLinksStylesAndEmbeddedFrames() {
        String cleaned = FeedParser.cleanHtml("<iframe src='/private'></iframe><a href='javascript:alert(1)' onclick='x()' style='color:red'>Link</a><img src='data:image/svg+xml,evil'><form><input></form><a href='/ok'>OK</a>", URL);
        assertThat(cleaned).doesNotContain("javascript:", "iframe", "style=", "onclick", "data:", "<form", "<input");
        assertThat(cleaned).contains("https://example.com/ok", "noopener noreferrer");
    }

    @Test void discoversOnlyDeclaredSafeFeedsAndHonorsBase() {
        var results = parser.discover("<html><head><base href='https://example.com/news/'><link rel='alternate' type='application/rss+xml' href='rss'><link rel='alternate' type='application/atom+xml' href='http://127.0.0.1/a'><link rel='stylesheet' href='x'></head></html>".getBytes(StandardCharsets.UTF_8), URL);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().url()).isEqualTo("https://example.com/news/rss");
    }

    @ParameterizedTest
    @ValueSource(strings={"http://example.com/feed", "https://user:password@example.com/feed", "https://127.0.0.1/a", "https://localhost/a", "https://example.com:8443/feed", "file:///etc/passwd", "https://example.com/a#fragment"})
    void rejectsUnsupportedUrls(String url) { assertThatThrownBy(() -> PublicNetworkPolicy.requireHttps(url)).isInstanceOf(FeedException.class); }

    @ParameterizedTest
    @ValueSource(strings={"127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "192.168.1.1", "198.18.0.1", "::1", "fc00::1", "fe80::1", "2002:7f00:1::"})
    void rejectsPrivateResolvedAddresses(String address) throws Exception { assertThat(PublicNetworkPolicy.isPublic(InetAddress.getByName(address))).isFalse(); }

    @Test void rejectsCompressionBombs() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(new byte[SafeXml.MAX_BYTES + 1]); }
        assertThatThrownBy(() -> PublicFeedTransport.decode(bytes.toByteArray(), "gzip", System.nanoTime() + Duration.ofSeconds(5).toNanos())).isInstanceOf(FeedException.class);
    }

    @Test void opmlRoundTripsEscapingAndNestedGroupsWithoutNetwork() {
        var codec = new OpmlCodec();
        var outlines = List.of(new FeedModels.Outline("https://example.com/feed?a=1&b=2", "A & B", "Tech / Java"));
        assertThat(codec.parse(codec.write(outlines).getBytes(StandardCharsets.UTF_8))).isEqualTo(outlines);
        assertThat(codec.parse("<opml><body><outline text='Tech'><outline text='Java'><outline text='Feed' xmlUrl='https://example.com/feed'/></outline></outline></body></opml>".getBytes(StandardCharsets.UTF_8)).getFirst().group()).isEqualTo("Tech / Java");
    }

    private FeedModels.Feed parse(String xml) { return parser.parse(xml.getBytes(StandardCharsets.UTF_8), URL); }
    private String rss(String item) { return "<rss version='2.0'><channel><title>Feed</title><item>" + item + "</item></channel></rss>"; }
}
