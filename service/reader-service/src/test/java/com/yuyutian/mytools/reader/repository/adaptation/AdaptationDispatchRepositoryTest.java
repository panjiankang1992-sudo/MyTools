package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAdaptationDispatchProperties;
import com.yuyutian.mytools.reader.config.ReaderAdaptationProperties;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationCommand;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.Action;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.SchedulerView;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationDispatchWorker;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationSchedulerException;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationSchedulerGateway;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reader 全量迁移与真实领取回写；Scheduler 仅提供确定性控制面夹具，不执行模型。 */
class AdaptationDispatchRepositoryTest {
    private static final String SHA = "a".repeat(64);
    private final MutableClock clock = new MutableClock();
    private final UUID shelf = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private final UUID binding = UUID.randomUUID();
    private final UUID chapter = UUID.randomUUID();
    private final SchedulerFixture scheduler = new SchedulerFixture();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private ChapterAdaptationRepository versions;
    private AdaptationDispatchRepository repository;
    private AdaptationDispatchWorker worker;
    private UUID adaptation;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:adaptation_dispatch_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        versions = new ChapterAdaptationRepository(jdbc, manager, new ObjectMapper().findAndRegisterModules(),
                new ReaderAdaptationProperties(true, true, "fixture", "fixture", "prompt-v1", "rules-v1", 2), clock);
        repository = new AdaptationDispatchRepository(jdbc, manager, new ReaderAdaptationDispatchProperties(true, 30, 3, 8, 5), clock);
        worker = new AdaptationDispatchWorker(repository, scheduler);
        Timestamp time = Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO shelf_book (id, owner_id, book_key, metadata_json, created_at, updated_at) VALUES (?, 41, ?, '{}', ?, ?)",
                shelf.toString(), shelf.toString(), time, time);
        jdbc.update("""
                INSERT INTO book_source (id, owner_id, sync_key, name, source_url, enabled, current_version, created_at, updated_at)
                VALUES (?, 41, ?, 'Fixture', 'https://fixture.invalid', TRUE, 1, ?, ?)
                """, source.toString(), source.toString(), time, time);
        jdbc.update("INSERT INTO book_source_version VALUES (?, 1, '{}', ?, ?)", source.toString(), SHA, time);
        jdbc.update("""
                INSERT INTO shelf_book_content_binding (id, owner_id, shelf_book_id, binding_type, source_id, source_version,
                    source_book_key, binding_revision, bound_shelf_version, status, catalog_revision, catalog_sha256, created_at, updated_at)
                VALUES (?, 41, ?, 'SOURCE_RUNTIME', ?, 1, ?, 1, 1, 'ACTIVE', 1, ?, ?, ?)
                """, binding.toString(), shelf.toString(), source.toString(), SHA, SHA, time, time);
        UUID locator = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO shelf_book_source_locator (id, owner_id, binding_id, binding_revision, source_version,
                    locator_scope, locator_ciphertext, locator_sha256, allowed_scheme, allowed_host, created_at)
                VALUES (?, 41, ?, 1, 1, 'CHAPTER', 'fixture-ciphertext', ?, 'https', 'fixture.invalid', ?)
                """, locator.toString(), binding.toString(), SHA, time);
        jdbc.update("""
                INSERT INTO shelf_book_chapter (id, owner_id, shelf_book_id, content_binding_id, binding_revision, catalog_revision,
                    chapter_index, chapter_key_sha256, chapter_title, locator_kind, source_locator_id, source_version,
                    content_kind, content_sha256, active, created_at, updated_at)
                VALUES (?, 41, ?, ?, 1, 1, 0, ?, 'Fixture chapter', 'SOURCE_CATALOG_KEY', ?, 1, 'TEXT', ?, TRUE, ?, ?)
                """, chapter.toString(), shelf.toString(), binding.toString(), SHA, locator.toString(), SHA, time, time);
        jdbc.update("INSERT INTO novel_adaptation_provider_deployment VALUES (?, 'fixture', ?, 1, ?, TRUE, ?)",
                binary("fixture"), binary("Fixture-Model"), SHA, time);
        com.yuyutian.mytools.reader.service.adaptation.AdaptationConsentFixtures.installAndAccept(jdbc,
                "fixture", "fixture", "rights", SHA, clock);
        adaptation = versions.create(new AdaptationCommand(41, shelf, chapter, AdaptationRequestKind.INITIAL, null,
                "fixture-request", "Add restrained dialogue without changing the ending.", 1, 1, SHA)).adaptationId();
    }

    @AfterEach
    void shutdown() {
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void shouldBindOnceThenObserveWithoutAnotherSubmissionOrContentRead() {
        assertThat(worker.processOne()).isTrue();
        assertThat(row().get("status")).isEqualTo("QUEUED");
        assertThat(row().get("task_instance_id")).isEqualTo(scheduler.task.toString());
        assertThat(worker.processOne()).isFalse();
        clock.advance(5);
        assertThat(worker.processOne()).isTrue();
        assertThat(scheduler.submits).isEqualTo(1);
        assertThat(scheduler.observations).isEqualTo(1);
        assertThat(row().get("dispatch_attempt_count")).isEqualTo(1);
        assertThat(row().get("provider_call_budget_remaining")).isEqualTo(5);
    }

    @Test
    void shouldRecoverLostSubmitResponseWithSameIdentity() {
        scheduler.submitFailures = 1;
        worker.processOne();
        assertThat(row().get("status")).isEqualTo("PENDING_DISPATCH");
        assertThat(row().get("task_instance_id")).isNull();
        UUID created = scheduler.task;
        clock.advance(30);
        worker.processOne();
        assertThat(row().get("task_instance_id")).isEqualTo(created.toString());
        assertThat(scheduler.submits).isEqualTo(2);
        assertThat(scheduler.cancels).isZero();
    }

    @Test
    void shouldFenceExpiredWorkerWithoutCancellingNewlyBoundSameTask() {
        var old = repository.claim("old");
        var response = scheduler.submit(adaptation);
        clock.advance(31);
        var replacement = repository.claim("replacement");
        assertThat(replacement.epoch()).isEqualTo(old.epoch() + 1);
        assertThat(repository.acknowledge(replacement, scheduler.submit(adaptation))).isFalse();
        assertThat(repository.acknowledge(old, response)).isFalse();
        assertThat(row().get("status")).isEqualTo("QUEUED");
        assertThat(scheduler.cancels).isZero();
    }

    @Test
    void shouldRequireCompensationForLateResponseAfterCancellation() {
        var old = repository.claim("old");
        var response = scheduler.submit(adaptation);
        versions.cancel(41, adaptation);
        clock.advance(31);
        var cleanup = repository.claim("cleanup");
        assertThat(cleanup.action()).isEqualTo(Action.CANCEL);
        repository.acknowledge(cleanup, scheduler.cancel(adaptation));
        assertThat(row().get("status")).isEqualTo("CANCELLED");
        assertThat(repository.acknowledge(old, response)).isTrue();
        assertThat(row().get("task_instance_id")).isEqualTo(response.taskInstanceId().toString());
    }

    @Test
    void shouldCancelAfterClaimBeforeSubmitEvenWhenNoSchedulerTaskExists() {
        var claim = repository.claim("worker");
        versions.cancel(41, adaptation);
        repository.failed(claim, true);
        clock.advance(5);
        worker.processOne();
        assertThat(scheduler.cancelled).isTrue();
        assertThat(scheduler.task).isNull();
        assertThat(row().get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void shouldRequireConfirmedBarrierBeforeExhaustedSubmitBecomesFailed() {
        scheduler.submitFailures = 3;
        for (int index = 0; index < 3; index++) {
            worker.processOne();
            clock.advance(30);
        }
        assertThat(row().get("dispatch_abort_error_code")).isEqualTo("READER_048");
        assertThat(row().get("status")).isEqualTo("PENDING_DISPATCH");
        scheduler.cancelFailures = 1;
        worker.processOne();
        assertThat(row().get("status")).isEqualTo("PENDING_DISPATCH");
        clock.advance(5);
        worker.processOne();
        assertThat(row().get("status")).isEqualTo("FAILED");
        assertThat(row().get("last_error_code")).isEqualTo("READER_048");
        assertThat(row().get("dispatch_attempt_count")).isEqualTo(3);
        assertThat(scheduler.submits).isEqualTo(3);
    }

    @Test
    void shouldRecordDeadlineWithoutStartingNewTask() {
        clock.advance(841);
        worker.processOne();
        assertThat(row().get("status")).isEqualTo("FAILED");
        assertThat(row().get("last_error_code")).isEqualTo("READER_058");
        assertThat(scheduler.submits).isZero();
        assertThat(scheduler.cancelled).isTrue();
    }

    @Test
    void shouldReconcileAfterShelfDeletedWhilePreservingOriginalRows() {
        worker.processOne();
        jdbc.update("UPDATE shelf_book SET deleted = TRUE WHERE id = ?", shelf.toString());
        clock.advance(5);
        worker.processOne();
        assertThat(row().get("status")).isEqualTo("FAILED");
        assertThat(row().get("last_error_code")).isEqualTo("READER_035");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shelf_book_chapter", Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldNotTurnSchedulerSuccessIntoAdoptedNovelCompletion() {
        worker.processOne();
        scheduler.status = "SUCCEEDED";
        clock.advance(5);
        worker.processOne();
        assertThat(row().get("status")).isEqualTo("QUEUED");
        assertThat(row().get("dispatch_abort_error_code")).isEqualTo("READER_048");
        clock.advance(5);
        worker.processOne();
        assertThat(row().get("status")).isEqualTo("FAILED");
        assertThat(versions.detail(41, adaptation).result()).isNull();
    }

    @Test
    void shouldWaitForRegisteredAttemptSettlementAfterSchedulerBarrier() {
        worker.processOne();
        insertPendingAttempt();
        versions.cancel(41, adaptation);
        clock.advance(5);
        worker.processOne();
        assertThat(row().get("status")).isEqualTo("CANCEL_REQUESTED");
        // 本夹具只证明恢复器必须等待结算，真实发送与结算协议由后续执行链实现。
        jdbc.update("""
                UPDATE novel_chapter_adaptation_attempt SET status = 'FAILED', terminal_payload_sha256 = ?, completed_at = ?
                WHERE adaptation_id = ?
                """, SHA, Timestamp.from(clock.instant()), adaptation.toString());
        clock.advance(5);
        worker.processOne();
        assertThat(row().get("status")).isEqualTo("CANCELLED");
        assertThat(row().get("last_error_code")).isNull();
    }

    @Test
    void shouldNormalizeNanosecondClockToDatabaseLeasePrecision() {
        clock.now = clock.now.plusNanos(123456789);
        worker.processOne();
        assertThat(row().get("status")).isEqualTo("QUEUED");
        assertThat(row().get("dispatch_claim_owner")).isNull();
    }

    @Test
    void shouldNotClaimTwiceConcurrentlyOrJoinCallerTransaction() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return repository.claim("first");
            });
            var second = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return repository.claim("second");
            });
            start.countDown();
            assertThat(Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)).filter(java.util.Objects::nonNull).count())
                    .isEqualTo(1);
        }
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(ignored -> repository.claim("nested")))
                .isInstanceOf(IllegalStateException.class);
    }

    private void insertPendingAttempt() {
        jdbc.update("""
                INSERT INTO novel_chapter_adaptation_attempt (id, adaptation_id, owner_id, attempt_no, call_kind,
                    task_instance_id, execution_id, fencing_token, scheduler_attempt_no, provider_deployment_id, model_id,
                    credential_generation, provider_attempt_id, request_sha256, status, settlement_token_sha256,
                    settlement_expires_at, chapter_delete_epoch, created_at)
                VALUES (?, ?, 41, 1, 'PLAN', ?, ?, 1, 1, ?, ?, 1, ?, ?, 'REGISTERED', ?, ?, 0, ?)
                """, UUID.randomUUID().toString(), adaptation.toString(), scheduler.task.toString(), UUID.randomUUID().toString(),
                binary("fixture"), binary("Fixture-Model"), UUID.randomUUID().toString(), SHA, SHA,
                Timestamp.from(clock.instant().plusSeconds(120)), Timestamp.from(clock.instant()));
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM novel_chapter_adaptation WHERE id = ?", adaptation.toString());
    }

    private static byte[] binary(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private final class SchedulerFixture implements AdaptationSchedulerGateway {
        private UUID task;
        private boolean cancelled;
        private int submits;
        private int observations;
        private int cancels;
        private int submitFailures;
        private int cancelFailures;
        private String status = "QUEUED";

        /** 夹具在抛出响应丢失之前先建立同键任务。 */
        @Override
        public SchedulerView submit(UUID id) {
            boundary(id);
            submits++;
            if (cancelled) {
                throw new AdaptationSchedulerException(false);
            }
            if (task == null) {
                task = UUID.randomUUID();
            }
            if (submitFailures-- > 0) {
                throw new AdaptationSchedulerException(true);
            }
            return view();
        }

        /** 查询不能隐式产生取消屏障。 */
        @Override
        public SchedulerView find(UUID id) {
            boundary(id);
            observations++;
            return view();
        }

        /** 取消确认与任务是否存在独立持久化。 */
        @Override
        public SchedulerView cancel(UUID id) {
            boundary(id);
            cancels++;
            if (cancelFailures-- > 0) {
                throw new AdaptationSchedulerException(true);
            }
            cancelled = true;
            status = "CANCELLED";
            return view();
        }

        private void boundary(UUID id) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(id).isEqualTo(adaptation);
        }

        private SchedulerView view() {
            return new SchedulerView(adaptation, task, cancelled, task == null ? null : status);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-10T00:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        /** 测试统一使用 UTC。 */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** 固定 UTC 时区不创建额外时钟。 */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** 返回测试当前时间。 */
        @Override
        public Instant instant() {
            return now;
        }
    }
}
