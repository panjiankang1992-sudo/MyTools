package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.service.ArticleExcerptReader;
import com.yuyutian.mytools.news.service.DigestEngine;
import com.yuyutian.mytools.news.service.FeedTransport;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ArticleExcerptReaderTest {
    private static final String SUMMARY = "The project adds incremental indexing for local files, reducing repeated scans while preserving existing search results.";

    @Test void publishedShortSummaryIsUsableWithoutRepeatingTheHeadline() {
        assertThat(SUMMARY.length()).isLessThan(180);
        assertThat(ArticleExcerptReader.sufficient("Incremental indexing", SUMMARY)).isTrue();
        assertThat(ArticleExcerptReader.sufficient(SUMMARY, SUMMARY)).isFalse();
        assertThat(ArticleExcerptReader.sufficient("Headline", "Only a headline.")).isFalse();
    }

    @Test void htmlExtractionKeepsOnlyArticleMaterialAndRejectsChallenges() {
        assertThat(extract("<article><nav>Unrelated navigation</nav><p>" + SUMMARY + "</p><div id='comments'><p>Unrelated comment</p></div></article>"))
                .isEqualTo(SUMMARY);
        assertThat(extract("<meta name='description' content='" + SUMMARY + "'><script>untrusted code</script>"))
                .isEqualTo(SUMMARY);
        assertThat(extract("<title>Just a moment...</title><meta name='description' content='" + SUMMARY + "'>")).isEmpty();
        assertThat(extract("<nav><p>" + SUMMARY + "</p></nav>")).isEmpty();
        String github = "<meta name='description' content='" + SUMMARY + "'><article class='markdown-body'><p>" + Fixtures.EVIDENCE + "</p></article>";
        assertThat(ArticleExcerptReader.extract(github.getBytes(StandardCharsets.UTF_8), "https://github.com/owner/project", "Project"))
                .isEqualTo(Fixtures.EVIDENCE.strip());
        assertThat(ArticleExcerptReader.extract(("<meta name='description' content='" + SUMMARY + "'>").getBytes(StandardCharsets.UTF_8),
                "https://github.com/owner/project", "Project")).isEmpty();
    }

    @Test void policyExistingEvidenceAndDeadlineAvoidExtraRequests() {
        var reader = new ArticleExcerptReader(source -> { throw new AssertionError("No page request expected"); });
        var articles = List.of(item(1, ""));
        assertThat(reader.enrich(state(EvidencePolicy.METADATA_ONLY), articles, Fixtures.CUTOFF, Long.MAX_VALUE)).isEqualTo(articles);
        assertThat(reader.enrich(state(EvidencePolicy.EXCERPT_ALLOWED), articles, Fixtures.CUTOFF, 0)).isEqualTo(articles);
        assertThat(reader.enrich(state(EvidencePolicy.EXCERPT_ALLOWED), articles, Fixtures.CUTOFF.plusSeconds(86400), Long.MAX_VALUE)).isEqualTo(articles);
        var sufficient = List.of(item(1, SUMMARY));
        assertThat(reader.enrich(state(EvidencePolicy.EXCERPT_ALLOWED), sufficient, Fixtures.CUTOFF, Long.MAX_VALUE)).isEqualTo(sufficient);
    }

    @Test void lazarusContentExcludesEditorControlsAndSidebar() {
        String html = "<div id='editConfirm'><p>Unrelated editor instruction</p></div>"
                + "<div id='content'>" + SUMMARY + "<br><div class='button-row'>Download button</div>"
                + "<pre>Unrelated code example</pre></div><div id='sidebar'><p>Unrelated recommendation</p></div>";
        assertThat(ArticleExcerptReader.extract(html.getBytes(StandardCharsets.UTF_8),
                "https://www.getlazarus.org/dynamic/", "Dynamic packages")).isEqualTo(SUMMARY);
    }

    @Test void externalExcerptRequestsPageInsteadOfFeedRepresentation() {
        var reader = new ArticleExcerptReader(new FeedTransport() {
            /** 原文步骤不能请求 Feed 表示。 */
            @Override public FeedResponse fetch(SourceState source) { throw new AssertionError("Feed representation is not the article"); }
            /** 返回原文页面表示。 */
            @Override public FeedResponse fetchPage(SourceState source) { return response("<article><p>" + SUMMARY + "</p></article>"); }
        });
        assertThat(reader.enrich(state(EvidencePolicy.EXCERPT_ALLOWED), List.of(item(1, "")), Fixtures.CUTOFF, Long.MAX_VALUE)
                .getFirst().content().evidenceText()).isEqualTo(SUMMARY);
    }

    @Test void jiangsuBodyUsesDeclaredEncodingAndExcludesSidebarMaterial() {
        String paragraph = "\u6c5f\u82cf\u5730\u65b9\u65b0\u95fb\u8be6\u7ec6\u62a5\u9053\u3002".repeat(10);
        String html = "<meta charset='GBK'><div id='cont'><div id='time'>2026-10-18 15:19:32</div>"
                + "<div id='txt'><p>" + paragraph + "</p></div></div><div id='right'><p>" + SUMMARY + "</p></div>";
        byte[] bytes = html.getBytes(java.nio.charset.Charset.forName("GB18030"));
        assertThat(ArticleExcerptReader.extract(bytes, "https://www.js.chinanews.com.cn/news/2026/1018/235682.html", "Title"))
                .isEqualTo(paragraph);
        assertThat(ArticleExcerptReader.extract(bytes, "https://example.com/story", "Title")).isEmpty();
    }

    @Test void jschinaBodyExcludesSidebarAndEditorialMaterial() {
        String html = "<div id='content' class='articalCont'><p>" + SUMMARY + "</p></div>"
                + "<div class='content_right'><p>Unrelated recommendation</p></div><div id='editor'>Editor name</div>";
        assertThat(ArticleExcerptReader.extract(html.getBytes(StandardCharsets.UTF_8),
                "https://jsnews.jschina.com.cn/jsyw/202610/t20261019_s123.shtml", "Title")).isEqualTo(SUMMARY);
        assertThat(extract(html)).isEmpty();
    }

    @Test void requestsAreLimitedAndDoNotCarryFeedConditionalHeaders() {
        List<String> requested = new ArrayList<>();
        var reader = new ArticleExcerptReader(source -> {
            requested.add(source.spec().feedUrl());
            assertThat(source.etag()).isNull();
            assertThat(source.lastModified()).isNull();
            assertThat(source.spec().kind()).isEqualTo(SourceKind.RSS_ATOM);
            return response("<article><p>" + SUMMARY + "</p></article>");
        });
        List<CollectedArticle> articles = new ArrayList<>();
        for (int index = 0; index < 20; index++) articles.add(item(index, ""));
        List<CollectedArticle> result = reader.enrich(state(EvidencePolicy.EXCERPT_ALLOWED), articles, Fixtures.CUTOFF, Long.MAX_VALUE);
        assertThat(requested).hasSize(10);
        assertThat(result.subList(0, 10)).allMatch(item -> item.content().evidenceText().equals(SUMMARY));
        assertThat(result.subList(10, 20)).allMatch(item -> item.content().evidenceText().isEmpty());
    }

    @Test void pageFailureRetainsHeadlineAndHnExternalTextCitesActualPage() {
        var original = List.of(item(1, ""));
        var failed = new ArticleExcerptReader(source -> { throw new NewsException(ErrorCode.NEWS_006); });
        assertThat(failed.enrich(state(EvidencePolicy.EXCERPT_ALLOWED), original, Fixtures.CUTOFF, Long.MAX_VALUE)).isEqualTo(original);
        SourceState hn = TechnicalCollectorsTest.state(SourceKind.HACKER_NEWS, EvidencePolicy.EXCERPT_ALLOWED);
        CollectedArticle entry = item(1, "");
        entry = new CollectedArticle(entry.content(), new TechnicalMetadata("1", "https://news.ycombinator.com/item?id=1", Fixtures.CUTOFF.minusSeconds(30), 20, 3, null));
        var reader = new ArticleExcerptReader(source -> response("<meta name='description' content='" + SUMMARY + "'>"));
        CollectedArticle enriched = reader.enrich(hn, List.of(entry), Fixtures.CUTOFF, Long.MAX_VALUE).getFirst();
        Article article = new Article("version", hn.spec(), enriched.content().url(), enriched.content().title(),
                Fixtures.CUTOFF.minusSeconds(30), Fixtures.CUTOFF, enriched.content().evidenceText(), "hash", 20, enriched.technical());
        Edition edition = new DigestEngine().build(UUID.randomUUID(), 1, Fixtures.CUTOFF, Fixtures.CUTOFF,
                ZoneId.of("UTC"), RegionSnapshot.unresolved(), List.of(article));
        assertThat(edition.sections().get(Category.TECH).items().getFirst().detail().citations().getFirst().url())
                .isEqualTo(entry.content().url());
        assertThat(enriched.technical().discussionUrl()).contains("news.ycombinator.com");
    }

    @Test void oldTechnicalJsonStillReadsWithoutExternalEvidenceUrl() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        TechnicalMetadata old = json.readValue("{\"externalId\":\"1\",\"discussionUrl\":\"https://news.ycombinator.com/item?id=1\",\"nativeScore\":10,\"commentCount\":2}", TechnicalMetadata.class);
        assertThat(old.evidenceUrl()).isNull();
    }

    @Test void existingFeedSummariesLeaveBudgetForLaterCandidatesAndRateLimitStopsReading() {
        List<CollectedArticle> articles = new ArrayList<>();
        for (int index = 0; index < 10; index++) articles.add(item(index, SUMMARY));
        articles.add(item(11, ""));
        List<String> requested = new ArrayList<>();
        var reader = new ArticleExcerptReader(source -> {
            requested.add(source.spec().feedUrl());
            return response("<article><p>" + SUMMARY + "</p></article>");
        });
        assertThat(reader.enrich(state(EvidencePolicy.EXCERPT_ALLOWED), articles, Fixtures.CUTOFF, Long.MAX_VALUE)
                .getLast().content().evidenceText()).isEqualTo(SUMMARY);
        assertThat(requested).containsExactly("https://example.com/story/11");
        var limited = new ArticleExcerptReader(source -> {
            requested.add(source.spec().feedUrl());
            throw new com.yuyutian.mytools.news.common.SourceRateLimitException(Fixtures.CUTOFF.plusSeconds(600));
        });
        requested.clear();
        var missing = List.of(item(1, ""), item(2, ""));
        assertThat(limited.enrich(state(EvidencePolicy.EXCERPT_ALLOWED), missing, Fixtures.CUTOFF, Long.MAX_VALUE)).isEqualTo(missing);
        assertThat(requested).hasSize(1);
    }

    private SourceState state(EvidencePolicy policy) {
        SourceSpec source = new SourceSpec("fixture", "Fixture", "https://example.com/feed.xml", Category.WORLD,
                "fixture", null, policy, "fixture", 0.8, true);
        return new SourceState(source, "feed-etag", "feed-modified", null, null, "FRESH");
    }
    private CollectedArticle item(int id, String text) {
        return new CollectedArticle(new ArticleInput("https://example.com/story/" + id, "Original title " + id,
                Fixtures.CUTOFF.minusSeconds(30), text, 0), null);
    }
    private String extract(String html) { return ArticleExcerptReader.extract(html.getBytes(StandardCharsets.UTF_8), "https://example.com/story", "Title"); }
    private FeedTransport.FeedResponse response(String html) { return new FeedTransport.FeedResponse(200, html.getBytes(StandardCharsets.UTF_8), null, null); }
}
