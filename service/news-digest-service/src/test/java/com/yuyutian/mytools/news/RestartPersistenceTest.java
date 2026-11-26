package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.Model.RegionModels.*;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import com.yuyutian.mytools.news.mapper.GithubSnapshotRepository;
import com.yuyutian.mytools.news.mapper.RegionRepository;
import com.yuyutian.mytools.news.mapper.SchedulingRepository;
import com.yuyutian.mytools.news.mapper.SourcePollingRepository;
import com.yuyutian.mytools.news.mapper.RegionJobRepository;
import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.service.DigestEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static com.yuyutian.mytools.news.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class RestartPersistenceTest {
    @TempDir Path directory;

    @Test
    void newApplicationContextReadsSameEditionAndEvidenceFromFileDatabase() {
        UUID id = UUID.randomUUID();
        Edition original = new DigestEngine().build(id, 17, CUTOFF, CUTOFF, ZoneId.of("Asia/Shanghai"), RegionSnapshot.unresolved(), List.of());
        SourceSpec github = new SourceSpec("github", "GitHub", "https://api.github.com/search/repositories", Category.TECH,
                "github", null, EvidencePolicy.METADATA_ONLY, "test-fixture-only", 0.8, true, SourceKind.GITHUB);
        var observedAt = CUTOFF.minus(Duration.ofHours(6));
        GithubSnapshot sample = new GithubSnapshot(123, "owner/project", "https://github.com/owner/project",
                CUTOFF.minus(Duration.ofDays(365)), observedAt, "", 100, 10, observedAt);
        RegionObservation observation = new RegionObservation(UUID.randomUUID(), "node", observedAt, ObservationStatus.RESOLVED,
                "a".repeat(64), "IPV4", "DIRECT", "MAXMIND_MMDB", "fixture-only", new GeoEvidence("CN", "SC", 123L, "Example", 20));
        RegionSnapshot frozen = new RegionSnapshot("SOURCE_PACK_MISSING", "GEONAME-123", "node", observedAt);
        UUID regionJobId;
        UUID runId;
        OutboxClaim initialClaim;
        try (var first = start()) {
            var scheduling = first.getBean(SchedulingRepository.class);
            scheduling.change(17, new SubscriptionChange(0, true, "UTC", "00:00"), CUTOFF.minusSeconds(86400));
            scheduling.scan(CUTOFF, "node");
            runId = scheduling.runs(17, 30).getFirst().id();
            initialClaim = scheduling.claim(CUTOFF).orElseThrow();
            NewsRepository repository = first.getBean(NewsRepository.class);
            repository.saveSource(source("world", Category.WORLD));
            var polling = first.getBean(SourcePollingRepository.class);
            polling.register("world", CUTOFF);
            polling.reserve(polling.claim(CUTOFF).orElseThrow(), CUTOFF.plusSeconds(1800), CUTOFF);
            regionJobId = first.getBean(RegionJobRepository.class).scan("node", CUTOFF).orElseThrow();
            assertThat(scheduling.claim(CUTOFF, "REGION")).isPresent();
            repository.ingest("world", List.of(new ArticleInput("https://example.com/a", "Persistent original event", CUTOFF.minusSeconds(1), EVIDENCE, 0)), CUTOFF);
            repository.saveEdition(original, "restart-key", "request-hash");
            repository.saveSource(github);
            assertThat(repository.acquireCollection("github", "snapshot", observedAt)).isTrue();
            repository.finishTechnicalCollection("github", "snapshot", observedAt,
                    new CollectedBatch(List.of(), List.of(sample), new CollectionCoverage(1, 1, 1, 0, 0, List.of())),
                    "FRESH", sample.createdAt());
            assertThat(repository.acquireCollection("github", "limited", CUTOFF)).isTrue();
            repository.cooldownCollection("github", "limited", CUTOFF, CUTOFF.plusSeconds(600));
            var regions = first.getBean(RegionRepository.class);
            regions.saveObservation(observation, observedAt);
            assertThat(regions.bind(17, "region-restart", "region-hash", CUTOFF, CUTOFF, () -> frozen)).isEqualTo(frozen);
        }
        try (var second = start()) {
            var scheduling = second.getBean(SchedulingRepository.class);
            assertThat(scheduling.run(17, runId).status()).isEqualTo("QUEUED");
            assertThat(scheduling.subscription(17)).isPresent();
            assertThat(scheduling.claim(CUTOFF.plusSeconds(1))).isEmpty();
            assertThat(scheduling.claim(CUTOFF.plusSeconds(121)).orElseThrow().token()).isNotEqualTo(initialClaim.token());
            assertThat(scheduling.scan(CUTOFF.plusSeconds(121), "node")).isZero();
            assertThat(second.getBean(SourcePollingRepository.class).states().getFirst().nextDueAt()).isEqualTo(CUTOFF.plusSeconds(1800));
            assertThat(second.getBean(SourcePollingRepository.class).claim(CUTOFF.plusSeconds(181))).isEmpty();
            assertThat(second.getBean(RegionJobRepository.class).job(regionJobId).status()).isEqualTo("QUEUED");
            assertThat(scheduling.claim(CUTOFF.plusSeconds(1), "REGION")).isEmpty();
            assertThat(scheduling.claim(CUTOFF.plusSeconds(121), "REGION")).isPresent();
            assertThat(second.getBean(RegionJobRepository.class).scan("node", CUTOFF.plusSeconds(121))).isEmpty();
            NewsRepository repository = second.getBean(NewsRepository.class);
            assertThat(repository.edition(17, id)).isEqualTo(original);
            assertThat(repository.findByKey(17, "restart-key", "request-hash")).contains(original);
            assertThat(repository.candidates(CUTOFF)).hasSize(2);
            assertThat(second.getBean(GithubSnapshotRepository.class).history("github", 123, CUTOFF)).containsExactly(sample);
            assertThat(repository.source("github").nextAttemptAt()).isEqualTo(CUTOFF.plusSeconds(600));
            assertThat(repository.source("github").health()).isEqualTo("RATE_LIMITED");
            assertThat(repository.source("github").newestObservedAt()).isEqualTo(observedAt);
            assertThat(repository.providerCooldown(SourceKind.GITHUB)).contains(CUTOFF.plusSeconds(600));
            assertThat(repository.acquireCollection("github", "too-soon", CUTOFF.plusSeconds(1))).isFalse();
            var regions = second.getBean(RegionRepository.class);
            assertThat(regions.observation(observation.id())).contains(observation);
            assertThat(regions.bind(17, "region-restart", "region-hash", CUTOFF, CUTOFF, RegionSnapshot::unresolved)).isEqualTo(frozen);
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(NewsDigestApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("news") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--news.internal-token=", "--news.admin-token=", "--spring.main.banner-mode=off");
    }
}
