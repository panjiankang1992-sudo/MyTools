package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.service.ArticleContentResolver;
import com.yuyutian.mytools.news.service.FeedTransport;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ArticleContentResolverTest {
    private static final String TEXT = "The project introduces incremental indexing, validates changed files against their original checksums, and avoids repeating full scans when only a few files change.";
    private static final String URL = "https://example.com/story";

    @Test void structuredArticleRequiresIdentityAndIgnoresUnrelatedOrPaidBody() {
        String article = "{\"@type\":\"NewsArticle\",\"url\":\"%s\",\"headline\":\"Title\",\"articleBody\":\"" + TEXT + "\"%s}";
        assertThat(extract("<script type='application/ld+json'>{\"@graph\":[" + article.formatted(URL, "") + "]}</script>").method()).isEqualTo("JSON_LD");
        assertThat(extract("<script type='application/ld+json'>" + article.formatted("https://example.com/unrelated", "") + "</script>").text()).isEmpty();
        assertThat(extract("<script type='application/ld+json'>" + article.formatted(URL, ",\"isAccessibleForFree\":false") + "</script>").text()).isEmpty();
        assertThat(extract("<script type='application/ld+json'>invalid</script><article>" + TEXT + "</article>").text()).isEqualTo(TEXT);
    }

    @Test void semanticDivsListsAndStatusUpdatesExcludeNoise() {
        for (String container : List.of("article", "div class='entry-content'", "div class='incident-updates-container'")) {
            String tag = container.split(" ")[0];
            assertThat(extract("<" + container + "><div>" + TEXT + "</div><aside>Unrelated content</aside>"
                    + "<div class='comments'>A speculative comment</div><div style='display: none'>Hidden content</div></" + tag + ">").text()).isEqualTo(TEXT);
        }
        assertThat(ArticleContentResolver.extract(("<article class='markdown-body'><ul><li>" + TEXT + "</li></ul></article>")
                .getBytes(StandardCharsets.UTF_8), "https://github.com/owner/repo", "Title").text()).isEqualTo(TEXT);
        assertThat(extract("<main><article>" + TEXT + "</article><article>Other story " + TEXT + "</article></main>").text()).isEmpty();
    }

    @Test void footerLegalNoticesAndPhotoCreditsDoNotCountAsNewsBody() {
        String legal = "\u672c\u7f51\u7ad9\u6240\u520a\u8f7d\u4fe1\u606f" + " site disclaimer ".repeat(8);
        String html = "<div class='left_zw'><p>A short factual photo caption.</p><p>\u6444\u5f71\uff1a" + "Name ".repeat(12)
                + "</p><p>\u7f16\u8f91\uff1a" + "Name ".repeat(12) + "</p></div><div class='pagebottom'>" + legal + "</div>";
        assertThat(extract(html).text()).isEmpty();
        assertThat(extract("<article>" + legal + "</article>").text()).isEmpty();
        assertThat(extract("<article>Copyright (c) 2026 " + "All Rights Reserved. ".repeat(8) + "</article>").text()).isEmpty();
        assertThat(extract("<article><p>" + TEXT + "</p></article><div class='footer'>" + legal + "</div>").text()).isEqualTo(TEXT);
    }

    @Test void paragraphFallbackRequiresMatchingHeadlineAndSubstantialParagraphs() {
        assertThat(extract("<h1>Title</h1><div><p>" + TEXT + "</p><p>Second paragraph " + TEXT + "</p></div>").method()).isEqualTo("PARAGRAPH_HTML");
        assertThat(extract("<h1>Other</h1><div><p>" + TEXT + "</p><p>" + TEXT + "</p></div>").text()).isEmpty();
    }

    @Test void ampMustBeSameOriginAndDeclareOriginalCanonical() {
        Map<String, String> pages = Map.of(URL, "<link rel='amphtml' href='/amp'>", URL.replace("story", "amp"),
                "<link rel='canonical' href='/story'><article>" + TEXT + "</article>");
        var result = resolve(pages, URL);
        assertThat(result.text()).isEqualTo(TEXT);
        assertThat(result.evidenceUrl()).endsWith("/amp");
        assertThat(result.attempts()).hasSize(2);
        assertThat(resolve(Map.of(URL, "<link rel='amphtml' href='https://other.com/amp'>"), URL).attempts()).hasSize(1);
        assertThat(resolve(Map.of(URL, "<link rel='amphtml' href='/amp'>", URL.replace("story", "amp"),
                "<link rel='canonical' href='/different'><article>" + TEXT + "</article>"), URL).text()).isEmpty();
    }

    @Test void wordpressApiRequiresExactArticleLinkAndNeverConsumesComments() {
        String api = "https://example.com/wp-json/wp/v2/posts/42";
        String link = "<link rel='alternate' type='application/json' href='" + api + "'>";
        String data = "{\"link\":\"%s\",\"content\":{\"rendered\":\"<p>" + TEXT + "</p>\"}}";
        assertThat(resolve(Map.of(URL, link, api, data.formatted(URL)), URL).method()).isEqualTo("WORDPRESS_API");
        assertThat(resolve(Map.of(URL, link, api, data.formatted("https://example.com/other")), URL).text()).isEmpty();
        assertThat(resolve(Map.of(URL, link.replace("posts/42", "comments/42")), URL).attempts()).hasSize(1);
    }

    @Test void githubApiPreservesBranchAndDoesNotFollowUnverifiedDownloadUrl() {
        String url = "https://github.com/owner/repo/tree/release-2";
        String api = "https://api.github.com/repos/owner/repo/readme?ref=release-2";
        String data = "{\"type\":\"file\",\"encoding\":\"base64\",\"html_url\":\"https://github.com/owner/repo/blob/release-2/README.md\",\"download_url\":\"http://127.0.0.1/secret\",\"content\":\""
                + Base64.getEncoder().encodeToString(TEXT.getBytes(StandardCharsets.UTF_8)) + "\"}";
        var result = resolve(Map.of(url, "<html></html>", api, data), url);
        assertThat(result.method()).isEqualTo("GITHUB_README");
        assertThat(result.evidenceUrl()).isEqualTo(api);
        assertThat(result.text()).isEqualTo(TEXT);
        assertThat(resolve(Map.of(url, "<html></html>", api, data.replace("blob/release-2", "blob/main")), url).text()).isEmpty();
        String directory = url + "/subdir";
        assertThat(resolve(Map.of(directory, "<html></html>"), directory).attempts()).hasSize(1);
    }

    @Test void budgetsPoliciesCacheAndRateLimitsBoundNetworkCalls() {
        List<String> requested = new ArrayList<>();
        var resolver = new ArticleContentResolver(state -> {
            requested.add(state.spec().feedUrl());
            throw new SourceRateLimitException(Instant.now().plusSeconds(300));
        });
        assertThat(resolver.resolve(source(EvidencePolicy.METADATA_ONLY), URL, "Title", Long.MAX_VALUE).status()).isEqualTo("NOT_ALLOWED");
        assertThat(resolver.resolve(source(EvidencePolicy.EXCERPT_ALLOWED), URL, "Title", 0).status()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(requested).isEmpty();
        resolver.resolve(source(EvidencePolicy.EXCERPT_ALLOWED), URL, "Title", Long.MAX_VALUE);
        resolver.resolve(source(EvidencePolicy.EXCERPT_ALLOWED), URL, "Title", Long.MAX_VALUE);
        resolver.resolve(source(EvidencePolicy.EXCERPT_ALLOWED), URL + "2", "Title", Long.MAX_VALUE);
        assertThat(requested).containsExactly(URL);
    }

    @Test void blockedPagesPrivateLinksAndFailedFallbackRetainHonestEvidence() {
        var blocked = resolve(Map.of(URL, "<title>Access denied</title><link rel='amphtml' href='/amp'><article>" + TEXT + "</article>"), URL);
        assertThat(blocked.text()).isEmpty(); assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.attempts()).hasSize(1);
        String meta = "<meta name='description' content='" + TEXT + "'><link rel='amphtml' href='/amp'>";
        var fallback = resolve(Map.of(URL, meta, URL.replace("story", "amp"), "<html></html>"), URL);
        assertThat(fallback.text()).isEqualTo(TEXT); assertThat(fallback.method()).isEqualTo("META_DESCRIPTION");
        assertThat(resolve(Map.of(URL, "<link rel='amphtml' href='https://127.0.0.1/secret'>"), URL).attempts()).hasSize(1);
        assertThat(resolve(Map.of("https://127.0.0.1/secret", "unexpected"), "https://127.0.0.1/secret").attempts().getFirst().get("outcome")).isEqualTo("NEWS_008");
    }

    @Test void declaredFeedUsesOnlyExactMatchingArticleAndRejectsEntities() {
        String link = "<link rel='alternate' type='application/rss+xml' href='/feed'>";
        String feed = "<rss><channel><item><title>Title</title><link>" + URL + "</link><description>" + TEXT + "</description></item>"
                + "<item><title>Other</title><link>https://example.com/other</link><description>Unrelated facts</description></item></channel></rss>";
        assertThat(resolve(Map.of(URL, link, "https://example.com/feed", feed), URL).text()).isEqualTo(TEXT);
        assertThat(resolve(Map.of(URL, link, "https://example.com/feed", feed.replace(URL + "</link>", URL + "2</link>")), URL).text()).isEmpty();
        String external = "<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><rss><channel>&xxe;</channel></rss>";
        assertThat(resolve(Map.of(URL, link, "https://example.com/feed", external), URL).text()).isEmpty();
    }

    @Test void originalPageCanonicalCannotPointToAnotherArticle() {
        assertThat(resolve(Map.of(URL, "<link rel='canonical' href='/other'><article>" + TEXT + "</article>"), URL).text()).isEmpty();
        assertThat(resolve(Map.of(URL, "<link rel='canonical' href='http://example.com/story/'><article>" + TEXT + "</article>"), URL).text()).isEqualTo(TEXT);
    }

    @Test void forumSelfLinksNeverTurnCommentsIntoArticleFacts() {
        var source = new SourceSpec("hn", "HN", "https://news.ycombinator.com", Category.TECH, "hn", null,
                EvidencePolicy.EXCERPT_ALLOWED, "fixture", 0.8, true, SourceKind.HACKER_NEWS);
        var resolver = new ArticleContentResolver(state -> { throw new AssertionError("No discussion page fetch"); });
        assertThat(resolver.resolve(source, "https://news.ycombinator.com/item?id=42", "Ask", Long.MAX_VALUE).status()).isEqualTo("DISCUSSION_ONLY");
    }

    @Test void atMostThreeAttemptsAndOriginalPageImageSurvivesAlternateBody() {
        String html = "<meta property='og:image' content='https://example.com/photo.jpg'>"
                + "<link rel='amphtml' href='/a'><link rel='amphtml' href='/b'><link rel='amphtml' href='/c'>";
        var result = resolve(Map.of(URL, html, "https://example.com/a", "empty", "https://example.com/b", "empty"), URL);
        assertThat(result.attempts()).hasSize(3);
        assertThat(result.image()).containsEntry("url", "https://example.com/photo.jpg");
    }

    private ArticleContentResolver.Extraction extract(String html) {
        return ArticleContentResolver.extract(html.getBytes(StandardCharsets.UTF_8), URL, "Title");
    }
    private ArticleContentResolver.Result resolve(Map<String, String> pages, String url) {
        var resolver = new ArticleContentResolver(state -> {
            String body = pages.get(state.spec().feedUrl());
            if (body == null) throw new AssertionError("Unexpected request: " + state.spec().feedUrl());
            return new FeedTransport.FeedResponse(200, body.getBytes(StandardCharsets.UTF_8), null, null);
        });
        return resolver.resolve(source(EvidencePolicy.EXCERPT_ALLOWED), url, "Title", Long.MAX_VALUE);
    }
    private SourceSpec source(EvidencePolicy policy) {
        return new SourceSpec("fixture", "Fixture", "https://example.com/feed", Category.TECH, "fixture", null, policy, "fixture", 0.8, true);
    }
}
