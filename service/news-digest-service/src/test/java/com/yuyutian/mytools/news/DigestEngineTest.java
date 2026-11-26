package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.service.DigestEngine;
import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import static com.yuyutian.mytools.news.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class DigestEngineTest {
    private final DigestEngine engine = new DigestEngine();
    private final UUID id = UUID.randomUUID();
    private final RegionSnapshot region = new RegionSnapshot("VERIFIED", "CN-TEST-CITY", "test-node", CUTOFF.minusSeconds(3600));

    @Test
    void weeklyCandidatesIncludeSevenDaysAndEvidenceFetchedAfterRequest() {
        var source = source("weekly-world", Category.WORLD);
        var original = article("weekly-start", source, CUTOFF.minusSeconds(7 * 86400), EVIDENCE);
        var fetchedLater = new Article(original.id(), source, original.url(), original.title(), original.publishedAt(),
                CUTOFF.plusSeconds(30), original.evidenceText(), original.contentHash(), 0);
        Edition base = engine.build(id, 7, CUTOFF.plusSeconds(60), CUTOFF.plusSeconds(60),
                ZoneId.of("Asia/Shanghai"), region, List.of());
        var weekly = engine.buildWeeklyCandidates(base, List.of(fetchedLater,
                article("too-old", source, CUTOFF.minusSeconds(7 * 86400 + 1), EVIDENCE),
                article("at-end", source, CUTOFF, EVIDENCE),
                article("no-time", source, null, EVIDENCE)), CUTOFF, 100);
        assertThat(weekly.sections().get(Category.WORLD).items()).extracting(RankedItem::title)
                .containsExactly("Unique reported event weekly-start");
        weekly.sections().values().forEach(section -> {
            assertThat(section.windowStart()).isEqualTo(CUTOFF.minusSeconds(7 * 86400));
            assertThat(section.windowEnd()).isEqualTo(CUTOFF);
        });
        assertThat(build(List.of(fetchedLater), region).sections().get(Category.WORLD).items()).isEmpty();
    }

    @Test
    void weeklyPoolExposesMoreThanTwentyCandidatesWithEvidenceForReranking() {
        List<Article> articles = new ArrayList<>();
        for (int index = 0; index < 105; index++) {
            articles.add(article("weekly-" + index, source("weekly-source", Category.DOMESTIC),
                    CUTOFF.minusSeconds(3 * 86400L + index), EVIDENCE));
        }
        Section section = engine.buildWeeklyCandidates(build(List.of(), region), articles, CUTOFF, 100)
                .sections().get(Category.DOMESTIC);
        assertThat(section.headlineCount()).isEqualTo(100);
        assertThat(section.detailCount()).isEqualTo(100);
    }

    @Test
    void fourCategoriesKeepTwentyHeadlinesAndOnlyTopTenDetails() {
        List<Article> articles = new ArrayList<>();
        for (Category category : Category.values()) {
            for (int index = 0; index < 25; index++) {
                articles.add(article(category + "-" + index, source("source-" + category.name().toLowerCase(), category),
                        CUTOFF.minusSeconds(60L + index), EVIDENCE));
            }
        }
        Edition edition = build(articles, region);
        assertThat(edition.quality()).isEqualTo(Quality.COMPLETE);
        edition.sections().values().forEach(section -> {
            assertThat(section.headlineCount()).isEqualTo(20);
            assertThat(section.detailCount()).isEqualTo(10);
            assertThat(section.items().subList(10, 20)).allMatch(item -> item.detail() == null);
            assertThat(section.items().getFirst().detail().citations()).hasSize(1);
        });
        Collections.shuffle(articles, new java.util.Random(7));
        assertThat(build(articles, region)).isEqualTo(edition);
    }

    @Test
    void personalCandidatePoolIncludesThirtyWithEvidenceWithoutChangingNormalEdition() {
        List<Article> articles = new ArrayList<>();
        for (int index = 0; index < 35; index++) {
            articles.add(article("candidate-" + index, source("world", Category.WORLD), CUTOFF.minusSeconds(index + 1), EVIDENCE));
        }
        Edition normal = build(articles, region);
        Section candidates = engine.buildCandidates(normal, articles).sections().get(Category.WORLD);
        assertThat(normal.sections().get(Category.WORLD).headlineCount()).isEqualTo(20);
        assertThat(normal.sections().get(Category.WORLD).detailCount()).isEqualTo(10);
        assertThat(candidates.headlineCount()).isEqualTo(30);
        assertThat(candidates.items()).allMatch(value -> value.detail() != null);
    }

    @Test
    void windowsAreStartInclusiveEndExclusiveAndObservedBeforeCutoff() {
        var tech = source("tech", Category.TECH);
        var world = source("world", Category.WORLD);
        Article unseen = article("unseen", world, CUTOFF.minusSeconds(1), EVIDENCE);
        unseen = new Article(unseen.id(), world, unseen.url(), unseen.title(), unseen.publishedAt(), CUTOFF.plusSeconds(1),
                unseen.evidenceText(), unseen.contentHash(), 0);
        Edition edition = build(List.of(article("tech-start", tech, CUTOFF.minusSeconds(7 * 86400), EVIDENCE),
                article("tech-old", tech, CUTOFF.minusSeconds(7 * 86400 + 1), EVIDENCE),
                article("world-start", world, CUTOFF.minusSeconds(86400), EVIDENCE),
                article("world-old", world, CUTOFF.minusSeconds(86401), EVIDENCE),
                article("future", world, CUTOFF, EVIDENCE), article("undated", world, null, EVIDENCE), unseen), region);
        assertThat(edition.sections().get(Category.TECH).items()).extracting(RankedItem::title).containsExactly("Unique reported event tech-start");
        assertThat(edition.sections().get(Category.WORLD).items()).extracting(RankedItem::title).containsExactly("Unique reported event world-start");
    }

    @Test
    void shortEvidenceDoesNotReplaceFirstRankWithEleventhItem() {
        List<Article> articles = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            articles.add(article("story-" + index, source("world", Category.WORLD), CUTOFF.minusSeconds(index + 1),
                    index == 0 ? "Only a headline." : EVIDENCE));
        }
        Section section = build(articles, region).sections().get(Category.WORLD);
        assertThat(section.detailCount()).isEqualTo(9);
        assertThat(section.items().getFirst().detailStatus()).isEqualTo(DetailStatus.EVIDENCE_TOO_SHORT);
        assertThat(section.items().get(10).detailStatus()).isEqualTo(DetailStatus.HEADLINE_ONLY);
        assertThat(section.gaps()).containsExactly("DETAIL_SHORTFALL");
    }

    @Test
    void unresolvedOrDifferentRegionDoesNotUseLocalArticles() {
        Article local = article("local", source("city", Category.LOCAL), CUTOFF.minusSeconds(1), EVIDENCE);
        assertThat(build(List.of(local), RegionSnapshot.unresolved()).sections().get(Category.LOCAL).gaps()).contains("REGION_UNRESOLVED");
        assertThat(build(List.of(local), new RegionSnapshot("VERIFIED", "CN-OTHER", "other", CUTOFF))
                .sections().get(Category.LOCAL).headlineCount()).isZero();
    }

    @Test
    void canonicalDuplicatesAppearOnlyInOneCategoryAndReprintsDoNotCountTwice() {
        SourceSpec first = source("first", Category.TECH);
        SourceSpec second = new SourceSpec("second", "second", "https://example.com/b.xml", Category.WORLD,
                first.publisherKey(), null, EvidencePolicy.EXCERPT_ALLOWED, "test", 0.8, true);
        Article original = article("original", first, CUTOFF.minusSeconds(100), EVIDENCE);
        Article reprint = new Article("reprint", second, original.url(), original.title(), original.publishedAt(),
                original.fetchedAt(), EVIDENCE, "hash", 0);
        Edition result = build(List.of(original, reprint), region);
        assertThat(result.sections().get(Category.WORLD).items()).isEmpty();
        RankedItem item = result.sections().get(Category.TECH).items().getFirst();
        assertThat(item.sourceIds()).containsExactly("first", "second");
        assertThat(item.score().corroboration()).isZero();
    }

    @Test
    void independentPublishersIncreaseCorroborationAndLongSameDayTitlesCluster() {
        Article original = article("original", source("first", Category.WORLD), CUTOFF.minusSeconds(100), EVIDENCE);
        Article related = new Article("second", source("second", Category.WORLD), "https://example.org/other", original.title(),
                original.publishedAt(), original.fetchedAt(), EVIDENCE, "hash", 0);
        RankedItem item = build(List.of(original, related), region).sections().get(Category.WORLD).items().getFirst();
        assertThat(item.sourceIds()).hasSize(2);
        assertThat(item.score().corroboration()).isEqualTo(0.5);
    }

    @Test
    void hackerNewsEvidenceCitesDiscussionAndDoesNotCountAsIndependentPublisher() {
        Article original = article("original", source("publisher", Category.TECH), CUTOFF.minusSeconds(100), "");
        SourceSpec hn = new SourceSpec("hn", "HN", "https://hacker-news.firebaseio.com/v0/topstories.json", Category.TECH,
                "hacker-news", null, EvidencePolicy.EXCERPT_ALLOWED, "test-fixture-only", 0.6, true, SourceKind.HACKER_NEWS);
        String discussion = "https://news.ycombinator.com/item?id=123";
        Article shared = new Article("shared", hn, original.url(), original.title(), original.publishedAt(),
                original.fetchedAt(), EVIDENCE, "hash", 30,
                new TechnicalMetadata("123", discussion, original.publishedAt(), 30, 2, null));
        RankedItem item = build(List.of(original, shared), region).sections().get(Category.TECH).items().getFirst();
        assertThat(item.url()).isEqualTo(original.url());
        assertThat(item.score().corroboration()).isZero();
        assertThat(item.detail().citations().getFirst().url()).isEqualTo(discussion);
        assertThat(item.detail().citations().getFirst().sourceId()).isEqualTo("hn");
    }

    @Test
    void correctedPublicationDateCannotResurrectOlderEligibleVersion() {
        Article old = article("old", source("world", Category.WORLD), CUTOFF.minusSeconds(100), EVIDENCE);
        Article corrected = new Article("new", old.source(), old.url(), old.title(), CUTOFF.minusSeconds(200000),
                CUTOFF, EVIDENCE, "new", 0);
        assertThat(build(List.of(old, corrected), region).sections().get(Category.WORLD).items()).isEmpty();
    }

    @Test
    void metadataOnlyPolicyPreventsDetailsEvenWhenTextIsPresent() {
        SourceSpec restricted = new SourceSpec("restricted", "restricted", "https://example.com/rss", Category.TECH,
                "restricted", null, EvidencePolicy.METADATA_ONLY, "test", 0.8, true);
        RankedItem item = build(List.of(article("restricted", restricted, CUTOFF.minusSeconds(1), EVIDENCE)), region)
                .sections().get(Category.TECH).items().getFirst();
        assertThat(item.detail()).isNull();
        assertThat(item.detailStatus()).isEqualTo(DetailStatus.NOT_AUTHORIZED);
    }

    @Test
    void technicalMixIncludesColdGithubCandidatesWithoutFabricatingGrowth() {
        List<Article> articles = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            articles.add(article("forum-" + index, source("forum", Category.TECH), CUTOFF.minusSeconds(index + 10), EVIDENCE));
        }
        for (int index = 0; index < 8; index++) articles.add(coldGithub(index));
        Edition edition = build(articles, region);
        Section section = edition.sections().get(Category.TECH);
        assertThat(section.headlineCount()).isEqualTo(20);
        List<RankedItem> github = section.items().stream().filter(item -> item.technical() != null).toList();
        assertThat(github).hasSize(5);
        assertThat(github).allSatisfy(item -> {
            assertThat(item.technical().github().netStars()).isNull();
            assertThat(item.score().engagement()).isZero();
            assertThat(item.score().freshness()).isZero();
        });
        assertThat(section.items().stream().map(RankedItem::eventId).distinct()).hasSize(20);
        Collections.shuffle(articles, new java.util.Random(9));
        assertThat(build(articles, region)).isEqualTo(edition);
    }

    @Test
    void missingGithubCandidatesDoNotLeaveReservedSlotsEmpty() {
        List<Article> articles = new ArrayList<>();
        for (int index = 0; index < 22; index++) {
            articles.add(article("forum-" + index, source("forum", Category.TECH), CUTOFF.minusSeconds(index + 10), EVIDENCE));
        }
        articles.add(coldGithub(1));
        articles.add(coldGithub(2));
        Section section = build(articles, region).sections().get(Category.TECH);
        assertThat(section.headlineCount()).isEqualTo(20);
        assertThat(section.items().stream().filter(item -> item.technical() != null)).hasSize(2);
        assertThat(build(List.of(coldGithub(1), coldGithub(2)), region).sections().get(Category.TECH).headlineCount()).isEqualTo(2);
    }

    private Article coldGithub(int index) {
        SourceSpec github = new SourceSpec("github", "GitHub", "https://api.github.com/search/repositories", Category.TECH,
                "github", null, EvidencePolicy.EXCERPT_ALLOWED, "fixture", 0.8, true, SourceKind.GITHUB);
        var observed = CUTOFF.minusSeconds(60);
        var trend = new GithubTrend(index + 1, 100000, 500, observed, null, null, null, 0, 1, TrendStatus.WARMING_UP);
        return new Article("github-" + index, github, "https://github.com/owner/project-" + index, "Repository project " + index,
                CUTOFF.minusSeconds(864000), observed, EVIDENCE, "hash", 0,
                new TechnicalMetadata(Integer.toString(index + 1), null, observed, 0, 0, trend));
    }

    private Edition build(List<Article> articles, RegionSnapshot location) {
        return engine.build(id, 1, CUTOFF, CUTOFF.plusSeconds(1), ZoneId.of("Asia/Shanghai"), location, articles);
    }
}
