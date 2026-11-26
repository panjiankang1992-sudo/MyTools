package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.config.TechSourceProperties;
import com.yuyutian.mytools.news.service.FeedTransport;
import com.yuyutian.mytools.news.service.technical.*;
import com.yuyutian.mytools.news.utils.RateLimitPolicy;
import com.yuyutian.mytools.news.utils.TechnicalSourcePolicy;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TechnicalCollectorsTest {
    private final ScriptedTransport transport = new ScriptedTransport();
    private final JsonSourceClient client = new JsonSourceClient(transport, new ObjectMapper());
    private final TechSourceProperties properties = new TechSourceProperties("", 30, 10, 30);

    @Test
    void hackerNewsSkipsJobsDeadDeletedOldAndNullWithoutFetchingExternalUrls() {
        SourceState source = state(SourceKind.HACKER_NEWS, EvidencePolicy.EXCERPT_ALLOWED);
        transport.respond(source.spec().feedUrl(), "[1,2,3,4,5,6,7,8,9]");
        transport.respond(hn(1), hnStory(1, "story", false, false, Fixtures.CUTOFF.minusSeconds(60).getEpochSecond()));
        transport.respond(hn(2), hnStory(2, "job", false, false, Fixtures.CUTOFF.minusSeconds(60).getEpochSecond()));
        transport.respond(hn(3), hnStory(3, "story", true, false, Fixtures.CUTOFF.minusSeconds(60).getEpochSecond()));
        transport.respond(hn(4), hnStory(4, "story", false, true, Fixtures.CUTOFF.minusSeconds(60).getEpochSecond()));
        transport.respond(hn(5), hnStory(5, "story", false, false, Fixtures.CUTOFF.minusSeconds(8 * 86400).getEpochSecond()));
        transport.respond(hn(6), "null");
        transport.respond(hn(7), hnStory(7, "comment", false, false, Fixtures.CUTOFF.getEpochSecond()));
        transport.respond(hn(8), "{\"id\":8,\"type\":\"story\",\"title\":\"Missing date\"}");
        transport.respond(hn(9), hnStory(9, "story", false, false, Fixtures.CUTOFF.plusSeconds(1).getEpochSecond()));
        CollectedBatch result = new HackerNewsCollector(client, properties).collect(source, Fixtures.CUTOFF);
        assertThat(result.articles()).hasSize(1);
        assertThat(result.coverage().skipped()).isEqualTo(7);
        assertThat(result.coverage().failed()).isEqualTo(1);
        assertThat(result.articles().getFirst().technical().discussionUrl()).isEqualTo("https://news.ycombinator.com/item?id=1");
        assertThat(result.articles().getFirst().content().publishedAt()).isEqualTo(Fixtures.CUTOFF.minusSeconds(60));
        assertThat(transport.requests).allMatch(url -> url.startsWith("https://hacker-news.firebaseio.com/v0/"));
    }

    @Test
    void hackerNewsFetchFailureIsPartialButRateLimitAbortsBatch() {
        SourceState source = state(SourceKind.HACKER_NEWS, EvidencePolicy.METADATA_ONLY);
        transport.respond(source.spec().feedUrl(), "[1,2]");
        transport.respond(hn(1), "not-json");
        transport.respond(hn(2), hnStory(2, "story", false, false, Fixtures.CUTOFF.minusSeconds(1).getEpochSecond()));
        var collector = new HackerNewsCollector(client, properties);
        assertThat(collector.collect(source, Fixtures.CUTOFF).coverage().failed()).isEqualTo(1);
        transport.rateLimitedUrl = hn(1);
        assertThatThrownBy(() -> collector.collect(source, Fixtures.CUTOFF)).isInstanceOf(SourceRateLimitException.class);
    }

    @Test
    void devMetadataOnlyNeverFetchesArticleBodiesAndIgnoresEditedOldArticles() {
        SourceState source = state(SourceKind.DEV, EvidencePolicy.METADATA_ONLY);
        transport.respond(devList(), "[" + devItem(1, Fixtures.CUTOFF.minusSeconds(1)) + "," + devItem(2, Fixtures.CUTOFF.minusSeconds(8 * 86400)) + "]");
        CollectedBatch result = new DevCollector(client, properties).collect(source, Fixtures.CUTOFF);
        assertThat(result.articles()).hasSize(1);
        assertThat(result.coverage().skipped()).isEqualTo(1);
        assertThat(transport.requests).containsExactly(devList());
        assertThat(result.articles().getFirst().content().engagement()).isEqualTo(12);
    }

    @Test
    void devPermittedDetailIsBoundToSameIdAndPublishedDate() {
        SourceState source = state(SourceKind.DEV, EvidencePolicy.EXCERPT_ALLOWED);
        transport.respond(devList(), "[" + devItem(1, Fixtures.CUTOFF.minusSeconds(1)) + "," + devItem(2, Fixtures.CUTOFF.minusSeconds(1)) + "]");
        transport.respond("https://dev.to/api/articles/1", "{\"id\":1,\"published_at\":\"" + Fixtures.CUTOFF.minusSeconds(1) + "\",\"body_html\":\"<p>Original complete body.</p>\"}");
        transport.respond("https://dev.to/api/articles/2", "{\"id\":999,\"published_at\":\"" + Fixtures.CUTOFF.minusSeconds(1) + "\",\"body_html\":\"Wrong article\"}");
        CollectedBatch result = new DevCollector(client, properties).collect(source, Fixtures.CUTOFF);
        assertThat(result.articles().getFirst().content().evidenceText()).isEqualTo("Original complete body.");
        assertThat(result.articles().getLast().content().evidenceText()).isEqualTo("List description");
        assertThat(result.coverage().failed()).isEqualTo(1);
    }

    @Test
    void unknownFieldsAreIgnoredButNonArrayAndOverlargeJsonAreRejected() {
        SourceState source = state(SourceKind.DEV, EvidencePolicy.METADATA_ONLY);
        transport.respond(devList(), "{\"message\":\"error page\"}");
        assertThatThrownBy(() -> new DevCollector(client, properties).collect(source, Fixtures.CUTOFF)).isInstanceOf(NewsException.class);
        transport.respond(devList(), " ".repeat(2097153));
        assertThatThrownBy(() -> client.get(source, devList())).isInstanceOf(NewsException.class);
    }

    @Test
    void sourceProtocolCannotRedirectCredentialsOrAttachToNonTechCategory() {
        SourceSpec source = state(SourceKind.GITHUB, EvidencePolicy.METADATA_ONLY).spec();
        TechnicalSourcePolicy.validate(source);
        assertThatThrownBy(() -> TechnicalSourcePolicy.validate(new SourceSpec(source.id(), source.name(), "https://evil.example/api", Category.TECH,
                source.publisherKey(), null, source.evidencePolicy(), "test", 0.5, true, SourceKind.GITHUB))).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> TechnicalSourcePolicy.validate(new SourceSpec(source.id(), source.name(), source.feedUrl(), Category.WORLD,
                source.publisherKey(), null, source.evidencePolicy(), "test", 0.5, true, SourceKind.GITHUB))).isInstanceOf(NewsException.class);
        assertThat(new TechSourceProperties("private-test-value", 30, 10, 30).toString()).doesNotContain("private-test-value");
    }

    @Test
    void rateHeadersUseBoundedConservativeCooldown() {
        Instant now = Fixtures.CUTOFF;
        assertThat(RateLimitPolicy.retryAt(now, "600", null)).isEqualTo(now.plusSeconds(600));
        assertThat(RateLimitPolicy.retryAt(now, "-5", null)).isEqualTo(now.plusSeconds(60));
        assertThat(RateLimitPolicy.retryAt(now, "Sun, 18 Oct 2026 00:10:00 GMT", null)).isEqualTo(now.plusSeconds(600));
        assertThat(RateLimitPolicy.retryAt(now, "120", Long.toString(now.plusSeconds(900).getEpochSecond()))).isEqualTo(now.plusSeconds(900));
        assertThat(RateLimitPolicy.retryAt(now, "999999999999999", null)).isEqualTo(now.plusSeconds(86400));
        assertThat(RateLimitPolicy.retryAt(now, "invalid", "invalid")).isEqualTo(now.plusSeconds(300));
    }

    static SourceState state(SourceKind kind, EvidencePolicy policy) {
        String endpoint = switch (kind) {
            case HACKER_NEWS -> "https://hacker-news.firebaseio.com/v0/topstories.json";
            case DEV -> "https://dev.to/api/articles";
            case GITHUB -> "https://api.github.com/search/repositories";
            default -> throw new IllegalArgumentException();
        };
        return new SourceState(new SourceSpec(kind.name().toLowerCase(), kind.name(), endpoint, Category.TECH,
                kind.name().toLowerCase(), null, policy, "test-fixture", 0.8, true, kind), null, null, null, null, "NEVER_FETCHED");
    }

    private String hn(long id) { return "https://hacker-news.firebaseio.com/v0/item/" + id + ".json"; }
    private String devList() { return "https://dev.to/api/articles?top=7&per_page=30&page=1"; }
    private String hnStory(long id, String type, boolean dead, boolean deleted, long time) {
        return "{\"id\":" + id + ",\"type\":\"" + type + "\",\"dead\":" + dead + ",\"deleted\":" + deleted
                + ",\"time\":" + time + ",\"title\":\"Original story " + id + "\",\"score\":12,\"descendants\":4,\"url\":\"https://example.org/story/" + id + "\"}";
    }
    private String devItem(long id, Instant published) {
        return "{\"id\":" + id + ",\"url\":\"https://dev.to/author/post-" + id + "\",\"title\":\"Original dev article " + id
                + "\",\"published_at\":\"" + published + "\",\"edited_at\":\"" + Fixtures.CUTOFF + "\",\"positive_reactions_count\":12,\"comments_count\":3,\"description\":\"List description\"}";
    }

    static class ScriptedTransport implements FeedTransport {
        final Map<String, String> responses = new HashMap<>();
        final List<String> requests = new ArrayList<>();
        String rateLimitedUrl;

        void respond(String url, String body) { responses.put(url, body); }

        /** 离线按 URL 验证适配器请求，未声明的外部访问直接使测试失败。 */
        @Override
        public FeedResponse fetch(SourceState source) {
            requests.add(source.spec().feedUrl());
            if (source.spec().feedUrl().equals(rateLimitedUrl)) throw new SourceRateLimitException(Fixtures.CUTOFF.plusSeconds(600));
            String body = responses.get(source.spec().feedUrl());
            if (body == null) throw new AssertionError("Unexpected test URL: " + source.spec().feedUrl());
            assertThat(source.etag()).isNull();
            assertThat(source.lastModified()).isNull();
            return new FeedResponse(200, body.getBytes(StandardCharsets.UTF_8), null, null);
        }
    }
}
