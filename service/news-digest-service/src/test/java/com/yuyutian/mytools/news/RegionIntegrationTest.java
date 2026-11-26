package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.Model.RegionModels.*;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.RegionProperties;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import com.yuyutian.mytools.news.mapper.RegionRepository;
import com.yuyutian.mytools.news.service.EditionService;
import com.yuyutian.mytools.news.service.RegionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:news-region;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "news.internal-token=test-proxy", "news.admin-token=test-admin", "news.region.node-id=executor-primary",
        "news.region.report-token=test-region-token-at-least-thirty-two-characters"
})
@AutoConfigureMockMvc
class RegionIntegrationTest {
    private static final String TOKEN = "test-region-token-at-least-thirty-two-characters";
    private static final Instant NOW = Fixtures.CUTOFF;
    @Autowired private RegionService regions;
    @Autowired private RegionRepository repository;
    @Autowired private NewsRepository news;
    @Autowired private EditionService editions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @BeforeEach
    void reset() {
        for (String table : List.of("news_region_snapshot", "news_region_source_pack", "news_region_observation", "news_edition", "news_article", "news_source")) {
            jdbc.update("DELETE FROM " + table);
        }
        Fixtures.resetControl(jdbc);
        Fixtures.resetRegistry(jdbc);
        clock.now.set(NOW);
    }

    @Test
    void reportTokenCannotManageSourcesAndGatewayTokenCannotForgeRegion() throws Exception {
        String body = json.writeValueAsString(report("a", ObservationStatus.RESOLVED, geo(123L, 20)));
        for (String token : List.of("test-admin", "test-proxy", "")) {
            mvc.perform(post("/internal/worker/news/region-observations").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/internal/admin/news/sources").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/news/local-region").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/worker/news/region-observations").header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORED"));
        mvc.perform(get("/internal/v1/news/local-region?city=forged").header("Authorization", "Bearer test-proxy"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.regionCode").value("GEONAME-123"))
                .andExpect(jsonPath("$.egressFingerprint").doesNotExist()).andExpect(jsonPath("$.details.egressFingerprint").doesNotExist());
    }

    @Test
    void rejectsWrongNodeStaleFutureInconsistentAndRawIpReports() throws Exception {
        RegionObservation valid = report("a", ObservationStatus.RESOLVED, geo(123L, 20));
        for (RegionObservation invalid : List.of(
                new RegionObservation(valid.id(), "other", NOW, valid.status(), valid.egressFingerprint(), "IPV4", "DIRECT", valid.provider(), "test", valid.geo()),
                new RegionObservation(valid.id(), valid.nodeId(), NOW.minusSeconds(301), valid.status(), valid.egressFingerprint(), "IPV4", "DIRECT", valid.provider(), "test", valid.geo()),
                new RegionObservation(valid.id(), valid.nodeId(), NOW.plusSeconds(31), valid.status(), valid.egressFingerprint(), "IPV4", "DIRECT", valid.provider(), "test", valid.geo()),
                new RegionObservation(valid.id(), valid.nodeId(), NOW.plusNanos(1), valid.status(), valid.egressFingerprint(), "IPV4", "DIRECT", valid.provider(), "test", valid.geo()),
                report("a", ObservationStatus.LOOKUP_FAILED, geo(123L, 20)))) {
            assertThatThrownBy(() -> regions.observe(invalid)).isInstanceOf(NewsException.class);
        }
        var raw = json.valueToTree(valid);
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw).put("publicIp", "8.8.8.8");
        mvc.perform(post("/internal/worker/news/region-observations").header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(raw))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_observation", Integer.class)).isZero();
    }

    @Test
    void freeProviderWithUnknownCityAccuracyUsesRegisteredProvinceCoverage() throws Exception {
        seedPack("CN-JS", "jiangsu", 1);
        RegionObservation observation=new RegionObservation(UUID.randomUUID(),"executor-primary",NOW,
                ObservationStatus.RESOLVED,"a".repeat(64),"IPV6","DIRECT","IPWHOIS","ipwho.is:json",
                new GeoEvidence("CN","JS",null,"Nanjing",null));
        mvc.perform(post("/internal/worker/news/region-observations").header("Authorization","Bearer "+TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(observation))).andExpect(status().isOk());
        RegionSnapshot location=regions.current();
        assertThat(location.status()).isEqualTo("PROVINCE_FALLBACK");
        assertThat(location.regionCode()).isEqualTo("CN-JS");
        assertThat(location.details().precision()).isEqualTo("PROVINCE");
        assertThat(location.details().accuracyRadiusKm()).isNull();
        assertThat(location.usable()).isTrue();
    }

    @Test
    void reportReplaysAfterTimeoutButConflictingPayloadCannotReplaceIt() {
        RegionObservation report = report("a", ObservationStatus.RESOLVED, geo(123L, 20));
        regions.observe(report);
        clock.now.set(NOW.plusSeconds(1000));
        assertThat(regions.observe(report).id()).isEqualTo(report.id());
        RegionObservation conflict = new RegionObservation(report.id(), report.nodeId(), report.observedAt(), report.status(),
                "b".repeat(64), report.addressFamily(), report.networkMode(), report.provider(), report.providerVersion(), report.geo());
        assertThatThrownBy(() -> regions.observe(conflict)).isInstanceOf(NewsException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_region_observation", Integer.class)).isEqualTo(1);
    }

    @Test
    void localEditionIncludesOnlySourcePackMembersAndFreezesSnapshotBeforeGeneration() {
        seedPack("GEONAME-123", "city", 1);
        news.saveSource(local("unlisted", "GEONAME-123"));
        news.saveSource(local("other", "GEONAME-456"));
        for (String id : List.of("city", "unlisted", "other")) {
            news.ingest(id, List.of(new ArticleInput("https://example.com/" + id, "Distinct local news " + id,
                    NOW.minusSeconds(60), Fixtures.EVIDENCE, 0)), NOW.minusSeconds(1));
        }
        regions.observe(report("a", ObservationStatus.RESOLVED, geo(123L, 20)));
        Edition edition = editions.preview(1, new PreviewRequest("city", NOW));
        assertThat(edition.region().status()).isEqualTo("VERIFIED");
        assertThat(edition.region().details().snapshotId()).isNotNull();
        assertThat(edition.sections().get(Category.LOCAL).items()).extracting(RankedItem::title).containsExactly("Distinct local news city");
        clock.now.set(NOW.plusSeconds(1));
        seedPack("GEONAME-456", "other", 1);
        regions.observe(report("b", ObservationStatus.RESOLVED, geo(456L, 20)));
        assertThat(editions.preview(1, new PreviewRequest("city", NOW))).isEqualTo(edition);
        assertThat(editions.preview(1, new PreviewRequest("next", clock.instant())).region().regionCode()).isEqualTo("GEONAME-456");
    }

    @Test
    void bindsBeforeFailureAndConcurrentRetriesKeepFirstRegionPerOwner() throws Exception {
        seedPack("GEONAME-123", "city", 1);
        regions.observe(report("a", ObservationStatus.RESOLVED, geo(123L, 20)));
        var first = regions.bind(1, "failed-build", "hash", NOW, NOW);
        clock.now.set(NOW.plusSeconds(1));
        regions.observe(report("b", ObservationStatus.RESOLVED, geo(456L, 20)));
        assertThat(regions.bind(1, "failed-build", "hash", NOW, clock.instant())).isEqualTo(first);
        assertThatThrownBy(() -> regions.bind(1, "failed-build", "changed", NOW, clock.instant())).isInstanceOf(NewsException.class);
        assertThat(regions.bind(2, "failed-build", "hash", clock.instant(), clock.instant()).regionCode()).isEqualTo("GEONAME-456");
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<RegionSnapshot>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> regions.bind(3, "concurrent", "hash", NOW, clock.instant())));
            List<UUID> ids = new ArrayList<>();
            for (Future<RegionSnapshot> future : futures) ids.add(future.get().details().snapshotId());
            assertThat(ids.stream().distinct()).hasSize(1);
        }
    }

    @Test
    void sameEgressFailureUsesSevenDayCacheButChangeOrUnknownBreaksContinuity() {
        seedPack("GEONAME-123", "city", 1);
        regions.observe(report("a", ObservationStatus.RESOLVED, geo(123L, 20)));
        clock.now.set(NOW.plus(Duration.ofDays(2)));
        regions.observe(report("a", ObservationStatus.LOOKUP_FAILED, null));
        assertThat(regions.current().status()).isEqualTo("CACHED");
        assertThat(regions.current().resolvedAt()).isEqualTo(NOW);
        clock.now.set(clock.instant().plusSeconds(1));
        regions.observe(report("b", ObservationStatus.LOOKUP_FAILED, null));
        assertThat(regions.current().status()).isEqualTo("LOOKUP_FAILED");
        clock.now.set(clock.instant().plusSeconds(1));
        regions.observe(report("a", ObservationStatus.LOOKUP_FAILED, null));
        assertThat(regions.current().status()).isEqualTo("LOOKUP_FAILED");
        clock.now.set(clock.instant().plusSeconds(1));
        regions.observe(report("a", ObservationStatus.RESOLVED, geo(123L, 20)));
        clock.now.set(clock.instant().plusSeconds(1));
        regions.observe(report(null, ObservationStatus.EGRESS_UNAVAILABLE, null));
        clock.now.set(clock.instant().plusSeconds(1));
        regions.observe(report("a", ObservationStatus.LOOKUP_FAILED, null));
        assertThat(regions.current().status()).isEqualTo("LOOKUP_FAILED");
    }

    @Test
    void oldObservationAndCacheExpiryDoNotSilentlyExtendFreshness() {
        seedPack("GEONAME-123", "city", 1);
        regions.observe(report("a", ObservationStatus.RESOLVED, geo(123L, 20)));
        clock.now.set(NOW.plus(Duration.ofHours(24)).plusSeconds(1));
        assertThat(regions.current().status()).isEqualTo("STALE");
        clock.now.set(NOW.plus(Duration.ofDays(7)).plusSeconds(1));
        regions.observe(report("a", ObservationStatus.LOOKUP_FAILED, null));
        assertThat(regions.current().status()).isEqualTo("LOOKUP_FAILED");
    }

    @Test
    void wideRadiusFallsBackToProvinceAndCountryOnlyCannotPopulateLocalNews() {
        seedPack("CN-SC", "province", 1);
        regions.observe(report("a", ObservationStatus.RESOLVED, geo(123L, 100)));
        RegionSnapshot province = regions.current();
        assertThat(province.status()).isEqualTo("PROVINCE_FALLBACK");
        assertThat(province.regionCode()).isEqualTo("CN-SC");
        assertThat(province.details().precision()).isEqualTo("PROVINCE");
        assertThat(province.details().cityName()).isNull();
        clock.now.set(NOW.plusSeconds(1));
        regions.observe(report("a", ObservationStatus.RESOLVED, new GeoEvidence("US", null, null, null, null)));
        assertThat(regions.current().status()).isEqualTo("COUNTRY_ONLY");
        assertThat(regions.current().usable()).isFalse();
    }

    @Test
    void cityWithoutRegisteredPackUsesExplicitProvinceFallbackAndUnknownRegionStaysEmpty() {
        seedPack("CN-SC", "province", 1);
        regions.observe(report("a", ObservationStatus.RESOLVED, geo(123L, 20)));
        assertThat(regions.current().status()).isEqualTo("PROVINCE_FALLBACK");
        clock.now.set(NOW.plusSeconds(1));
        regions.observe(report("b", ObservationStatus.RESOLVED, new GeoEvidence("US", "CA", 789L, "Example", 20)));
        assertThat(regions.current().status()).isEqualTo("SOURCE_PACK_MISSING");
        assertThat(regions.current().details().sourceIds()).isEmpty();
    }

    @Test
    void disablingProvinceFallbackDoesNotSilentlySelectProvincialSources() {
        seedPack("CN-SC", "province", 1);
        regions.observe(report("a", ObservationStatus.RESOLVED, geo(123L, 100)));
        var strict = new RegionService(repository, news, new RegionProperties("executor-primary", "", 50, false), clock);
        assertThat(strict.current().usable()).isFalse();
        assertThat(strict.current().details().sourceIds()).isEmpty();
    }

    @Test
    void lateReportsAndNewPackVersionsCannotRewriteCutoffOrFrozenSelection() {
        seedPack("GEONAME-123", "city", 1);
        RegionObservation observed = report("a", ObservationStatus.RESOLVED, geo(123L, 20));
        clock.now.set(NOW.plusSeconds(1));
        regions.observe(observed);
        assertThat(regions.statusAt(NOW)).isEqualTo("UNRESOLVED");
        RegionSnapshot first = regions.bind(1, "before-pack", "hash", clock.instant(), clock.instant());
        clock.now.set(NOW.plusSeconds(2));
        seedPack("GEONAME-123", "city-v2", 2);
        assertThat(regions.current().details().sourcePackVersion()).isEqualTo(2);
        assertThat(regions.bind(1, "before-pack", "hash", NOW.plusSeconds(1), clock.instant())).isEqualTo(first);
        assertThat(repository.pack("GEONAME-123", NOW).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void packsRejectWrongCategoryRegionDuplicatesFutureVerificationAndChangedVersions() {
        news.saveSource(Fixtures.source("world", Category.WORLD));
        news.saveSource(local("city", "GEONAME-123"));
        for (RegionSourcePack pack : List.of(new RegionSourcePack("GEONAME-123", 1, "Test", List.of("world"), NOW),
                new RegionSourcePack("GEONAME-456", 1, "Test", List.of("city"), NOW),
                new RegionSourcePack("GEONAME-123", 1, "Test", List.of("city", "city"), NOW),
                new RegionSourcePack("GEONAME-123", 1, "Test", List.of("city"), NOW.plusSeconds(1)))) {
            assertThatThrownBy(() -> regions.registerPack(pack)).isInstanceOf(NewsException.class);
        }
        var pack = new RegionSourcePack("GEONAME-123", 1, "Test", List.of("city"), NOW);
        regions.registerPack(pack);
        assertThat(regions.registerPack(pack)).isEqualTo(pack);
        assertThatThrownBy(() -> regions.registerPack(new RegionSourcePack("GEONAME-123", 1, "Changed", List.of("city"), NOW)))
                .isInstanceOf(NewsException.class);
    }

    private void seedPack(String regionCode, String sourceId, int version) {
        news.saveSource(local(sourceId, regionCode));
        regions.registerPack(new RegionSourcePack(regionCode, version, "Example region", List.of(sourceId), clock.instant()));
    }

    private SourceSpec local(String id, String code) {
        return new SourceSpec(id, id, "https://example.com/" + id, Category.LOCAL, id, code, EvidencePolicy.EXCERPT_ALLOWED, "fixture-only", 0.8, true);
    }

    private GeoEvidence geo(Long cityId, Integer radius) { return new GeoEvidence("CN", "SC", cityId, "Example City", radius); }

    private RegionObservation report(String fingerprint, ObservationStatus status, GeoEvidence geo) {
        return new RegionObservation(UUID.randomUUID(), "executor-primary", clock.instant(), status, fingerprint == null ? null : fingerprint.repeat(64),
                fingerprint == null ? null : "IPV4", "DIRECT", "MAXMIND_MMDB", "fixture-only", geo);
    }

    /** 地区测试专用时钟配置。 */
    @TestConfiguration
    static class TestBeans {
        /** 提供可控制观测与接收时间的时钟。 */
        @Bean @Primary public MutableClock regionClock() { return new MutableClock(); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        /** 返回统一测试时区。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 创建对应时刻的固定时钟。 */
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        /** 返回当前测试时刻。 */
        @Override public Instant instant() { return now.get(); }
    }
}
