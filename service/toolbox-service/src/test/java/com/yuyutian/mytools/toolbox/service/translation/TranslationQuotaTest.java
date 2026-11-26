package com.yuyutian.mytools.toolbox.service.translation;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.config.TranslationProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用真实事务和独立内存数据库验证持久化额度，不访问网络。 */
class TranslationQuotaTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // 每项测试独立建库，并直接执行真实迁移，避免手写表结构与生产迁移漂移。
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:translation_quota_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__translation_quota.sql"))
                .execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) {
            // 关闭保活的内存库，避免测试进程中积累历史数据库。
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void allowsExactGlobalLimitAndRejectsFurtherGuestsWithoutCreatingRows() {
        TranslationQuota quota = quota(10, 5, CLOCK);

        assertThat(quota.tryConsume("guest:first", 5)).isTrue();
        assertThat(quota.tryConsume("guest:second", 5)).isTrue();
        assertThat(quota.tryConsume("guest:third", 1)).isFalse();

        assertThat(usage(TODAY, "global")).isEqualTo(10);
        assertThat(usage(TODAY, "guest:first")).isEqualTo(5);
        assertThat(usage(TODAY, "guest:second")).isEqualTo(5);
        assertThat(rowCount(TODAY, "guest:third")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void allowsExactGuestLimitAndRejectsOneMoreCharacter() {
        TranslationQuota quota = quota(10, 4, CLOCK);

        assertThat(quota.tryConsume("guest:first", 3)).isTrue();
        assertThat(quota.tryConsume("guest:first", 1)).isTrue();
        assertThat(quota.tryConsume("guest:first", 1)).isFalse();

        assertThat(usage(TODAY, "guest:first")).isEqualTo(4);
        assertThat(usage(TODAY, "global")).isEqualTo(4);
    }

    @Test
    void rollsBackGlobalDebitWhenGuestLimitRejectsRequest() {
        TranslationQuota quota = quota(10, 4, CLOCK);

        assertThat(quota.tryConsume("guest:first", 4)).isTrue();
        assertThat(quota.tryConsume("guest:first", 2)).isFalse();
        assertThat(usage(TODAY, "global")).isEqualTo(4);

        // 拒绝的访客请求不能消耗其他访客本应可用的剩余额度。
        assertThat(quota.tryConsume("guest:second", 4)).isTrue();
        assertThat(quota.tryConsume("guest:third", 2)).isTrue();
        assertThat(usage(TODAY, "global")).isEqualTo(10);
        assertThat(usage(TODAY, "guest:first")).isEqualTo(4);
        assertThat(usage(TODAY, "guest:second")).isEqualTo(4);
        assertThat(usage(TODAY, "guest:third")).isEqualTo(2);
    }

    @Test
    void preservesUsageWhenQuotaAndTransactionManagerAreRecreated() {
        TranslationQuota original = quota(10, 6, CLOCK);
        assertThat(original.tryConsume("guest:returning", 4)).isTrue();

        // 重新构造全部额度访问对象，确认限额来自数据库而非实例字段。
        TranslationQuota recreated = quota(10, 6, CLOCK);
        assertThat(recreated.tryConsume("guest:returning", 3)).isFalse();
        assertThat(recreated.tryConsume("guest:returning", 2)).isTrue();
        assertThat(recreated.tryConsume("guest:newcomer", 4)).isTrue();
        assertThat(original.tryConsume("guest:another", 1)).isFalse();

        assertThat(usage(TODAY, "global")).isEqualTo(10);
        assertThat(usage(TODAY, "guest:returning")).isEqualTo(6);
        assertThat(usage(TODAY, "guest:newcomer")).isEqualTo(4);
    }

    @Test
    void resetsAtUtcMidnightRegardlessOfClockZone() {
        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-10-04T23:59:59Z"), ZoneOffset.ofHours(14));
        Clock afterMidnight = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.ofHours(-12));
        TranslationQuota firstDay = quota(6, 6, beforeMidnight);

        assertThat(firstDay.tryConsume("guest:returning", 6)).isTrue();
        assertThat(firstDay.tryConsume("guest:returning", 1)).isFalse();

        TranslationQuota nextDay = quota(6, 6, afterMidnight);
        assertThat(nextDay.tryConsume("guest:returning", 6)).isTrue();
        assertThat(nextDay.tryConsume("guest:returning", 1)).isFalse();
        assertThat(usage(TODAY, "global")).isEqualTo(6);
        assertThat(usage(TODAY, "guest:returning")).isEqualTo(6);
        assertThat(usage(TODAY.plusDays(1), "global")).isEqualTo(6);
        assertThat(usage(TODAY.plusDays(1), "guest:returning")).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota", Integer.class))
                .isEqualTo(4);
    }

    @Test
    void deletesOnlyRowsOlderThanTwoUtcDays() {
        seed(TODAY.minusDays(3), "global", 6);
        seed(TODAY.minusDays(3), "guest:expired", 6);
        seed(TODAY.minusDays(2), "global", 7);
        seed(TODAY.minusDays(2), "guest:retained", 7);
        seed(TODAY.minusDays(1), "global", 8);
        seed(TODAY.minusDays(1), "guest:recent", 8);

        assertThat(quota(10, 10, CLOCK).tryConsume("guest:today", 2)).isTrue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota WHERE quota_day < ?",
                Integer.class, Date.valueOf(TODAY.minusDays(2)))).isZero();
        assertThat(usage(TODAY.minusDays(2), "global")).isEqualTo(7);
        assertThat(usage(TODAY.minusDays(2), "guest:retained")).isEqualTo(7);
        assertThat(usage(TODAY.minusDays(1), "global")).isEqualTo(8);
        assertThat(usage(TODAY.minusDays(1), "guest:recent")).isEqualTo(8);
        assertThat(usage(TODAY, "global")).isEqualTo(2);
        assertThat(usage(TODAY, "guest:today")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota", Integer.class))
                .isEqualTo(6);
    }

    /** 统计 classpath 上现有的迁移脚本数量。 */
    private static int availableMigrationCount() throws IOException {
        ClassPathResource directory = new ClassPathResource("db/migration");
        try (var paths = Files.list(Path.of(directory.getURI()))) {
            return (int) paths.filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql")).count();
        }
    }

    @Test
    void rejectsNonPositiveCountsWithoutConsumingBudget() {
        TranslationQuota quota = quota(10, 4, CLOCK);

        assertThat(quota.tryConsume("guest:invalid", 0)).isFalse();
        assertThat(quota.tryConsume("guest:invalid", -1)).isFalse();
        assertThat(usage(TODAY, "global")).isZero();
        assertThat(rowCount(TODAY, "guest:invalid")).isZero();
        assertThat(quota.tryConsume("guest:valid", 4)).isTrue();
        assertThat(usage(TODAY, "global")).isEqualTo(4);
    }

    @Test
    void olderReleaseWithOnlyV1CanMigrateWithoutLosingV2Usage(@TempDir Path olderMigrations) throws IOException {
        // 此独立测试库尚无数据，先恢复空库，再由 Flyway 完整执行发布迁移。
        jdbc.execute("DROP TABLE toolbox_translation_quota");
        Flyway currentRelease = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").load();
        // 断言"全部待执行迁移都成功"，而不是写死数量——否则每新增一个迁移都会误报失败。
        assertThat(currentRelease.migrate().migrationsExecuted).isEqualTo(availableMigrationCount());
        assertThat(quota(10, 6, CLOCK).tryConsume("guest:returning", 4)).isTrue();

        // 旧包仅带 V1，保留其默认未来版本策略，不能用忽略规则掩盖不兼容。
        try (InputStream script = new ClassPathResource("db/migration/V1__toolbox_public.sql").getInputStream()) {
            Files.copy(script, olderMigrations.resolve("V1__toolbox_public.sql"));
        }
        Flyway olderRelease = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:" + olderMigrations.toAbsolutePath()).load();
        olderRelease.validate();
        assertThat(olderRelease.migrate().migrationsExecuted).isZero();
        // 旧包只看得到 V1，但库里已应用到当前最新迁移，因此当前版本是最新号且状态为 FUTURE_SUCCESS。
        assertThat(olderRelease.info().current().getVersion().getVersion())
                .isEqualTo(String.valueOf(availableMigrationCount()));
        assertThat(olderRelease.info().current().getState()).isEqualTo(MigrationState.FUTURE_SUCCESS);

        assertThat(usage(TODAY, "global")).isEqualTo(4);
        assertThat(usage(TODAY, "guest:returning")).isEqualTo(4);
        assertThat(quota(10, 6, CLOCK).tryConsume("guest:returning", 3)).isFalse();
        assertThat(usage(TODAY, "global")).isEqualTo(4);
    }

    @Test
    @Timeout(30)
    void concurrentDistinctGuestsNeverExceedGlobalLimit() throws Exception {
        TranslationQuota quota = quota(12, 1, CLOCK);

        assertThat(consumeConcurrently(quota, 24, index -> "guest:visitor-" + index)).isEqualTo(12);

        assertThat(usage(TODAY, "global")).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT SUM(used_characters) FROM toolbox_translation_quota "
                + "WHERE quota_day = ? AND quota_scope <> ?", Integer.class, Date.valueOf(TODAY), "global"))
                .isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota WHERE quota_day = ?",
                Integer.class, Date.valueOf(TODAY))).isEqualTo(13);
    }

    @Test
    @Timeout(30)
    void concurrentSameGuestNeverExceedsGuestLimitOrLeaksGlobalBudget() throws Exception {
        TranslationQuota quota = quota(12, 4, CLOCK);

        assertThat(consumeConcurrently(quota, 24, index -> "guest:shared")).isEqualTo(4);

        assertThat(usage(TODAY, "global")).isEqualTo(4);
        assertThat(usage(TODAY, "guest:shared")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota WHERE quota_day = ?",
                Integer.class, Date.valueOf(TODAY))).isEqualTo(2);
    }

    @Test
    void chargesExactlyTheScopeTheCallerGaveWithoutAddingAnyPrefix() {
        // F06：额度主体必须**原样**记账，因为管理端要靠它拿到与访客**互不影响**的额度。
        //
        // 这条测试挡的是一个很隐蔽的错法：本类**曾经自己拼前缀**（`"guest:" + guestId`）。
        // 如果只把调用方改成传 `"admin:…"` 而不改那一行，落库会变成 `guest:admin:…`——
        // **接口通了、别的测试也绿了，但管理员和访客仍然共用额度**。
        // 所以断言"按什么 scope 落库"，而不是"扣费成功了"。
        TranslationQuota quota = quota(100, 50, CLOCK);

        assertThat(quota.tryConsume("admin:u-1", 5)).isTrue();

        assertThat(usage(TODAY, "admin:u-1")).isEqualTo(5);
        // 反证：不能出现被改过前缀的行。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota "
                + "WHERE quota_day = ? AND quota_scope LIKE ?", Integer.class,
                Date.valueOf(TODAY), "%admin:u-1")).isEqualTo(1);
    }

    private TranslationQuota quota(int globalLimit, int guestLimit, Clock clock) {
        TranslationProperties properties = new TranslationProperties(globalLimit, guestLimit, 2, 2048, 86400, 25000);
        return new TranslationQuota(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), properties, clock);
    }

    private int usage(LocalDate day, String scope) {
        return jdbc.queryForObject("SELECT used_characters FROM toolbox_translation_quota "
                + "WHERE quota_day = ? AND quota_scope = ?", Integer.class, Date.valueOf(day), scope);
    }

    private int rowCount(LocalDate day, String scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota "
                + "WHERE quota_day = ? AND quota_scope = ?", Integer.class, Date.valueOf(day), scope);
    }

    private void seed(LocalDate day, String scope, int characters) {
        jdbc.update("INSERT INTO toolbox_translation_quota (quota_day, quota_scope, used_characters) VALUES (?, ?, ?)",
                Date.valueOf(day), scope, characters);
    }

    private int consumeConcurrently(TranslationQuota quota, int requests, IntFunction<String> guestIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requests; index++) {
                // 首批工作线程共用起跑信号，覆盖额度行首次插入和扣减时的竞争。
                String guestId = guestIds.apply(index);
                futures.add(executor.submit(() -> {
                    start.await();
                    return quota.tryConsume(guestId, 1);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(15, TimeUnit.SECONDS)) {
                    // 只统计成功扣费的请求，异常和超时直接使测试失败。
                    accepted++;
                }
            }
            return accepted;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
