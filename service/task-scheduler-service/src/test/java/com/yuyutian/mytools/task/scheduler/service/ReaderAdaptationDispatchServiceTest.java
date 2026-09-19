package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** V1—V140 与真实 V145 的事务夹具；V141—V144 的 MySQL JSON 函数不由 H2 模拟。 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:reader_dispatch;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.flyway.target=140", "task.security.required=true",
        "task.security.business-clients.reader-service=fixture-reader-business-token",
        "task.security.business-clients.other-service=fixture-other-business-token"})
@AutoConfigureMockMvc
class ReaderAdaptationDispatchServiceTest {
    @Autowired
    private ReaderAdaptationDispatchService service;
    @Autowired
    private ReaderAdaptationTaskGuard guard;
    @Autowired
    private TaskInstanceService instances;
    @Autowired
    private TaskDefinitionService definitions;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        // 只隔离不相关的下载任务种子更新，屏障表仍执行待交付的原始 SQL。
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'reader_adaptation_dispatch_guard'",
                Integer.class) == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V145__add_reader_adaptation_dispatch_guard.sql"))
                    .execute(Objects.requireNonNull(jdbc.getDataSource()));
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V146__add_task_execution_authorization.sql"))
                    .execute(Objects.requireNonNull(jdbc.getDataSource()));
        }
        readerIdentity();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM task_definition WHERE name = ?", Integer.class, ReaderAdaptationTaskGuard.TASK_NAME) == 0) {
            definitions.create(new CreateTaskDefinitionRequest(ReaderAdaptationTaskGuard.TASK_NAME, "Fixture adaptation definition",
                    TaskType.IMMEDIATE, 900, null, null, null, ExecutionMode.SINGLE_NODE, true, 2,
                    "SKIP", "IGNORE", Map.of("type", "object", "required", List.of("adaptationId"), "additionalProperties", false,
                    "properties", Map.of("adaptationId", Map.of("type", "string"))), Map.of()));
        }
        jdbc.update("UPDATE task_definition SET enabled = TRUE WHERE name = ?", ReaderAdaptationTaskGuard.TASK_NAME);
    }

    @AfterEach
    void clearIdentity() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldSubmitOnlyCanonicalOpaqueIdAndReplayAfterDefinitionDisabled() {
        UUID id = UUID.randomUUID();
        var first = service.submit(id);
        assertThat(first.taskStatus()).isEqualTo("QUEUED");
        assertThat(first.cancellationRecorded()).isFalse();
        assertThat(service.submit(id)).isEqualTo(first);
        var stored = instances.get(first.taskInstanceId());
        assertThat(stored.parameters()).containsOnlyKeys("adaptationId");
        assertThat(stored.requiredNodeLabels()).containsExactlyEntriesOf(Map.of("reader.adaptation", "enabled"));
        jdbc.update("UPDATE task_definition SET enabled = FALSE WHERE name = ?", ReaderAdaptationTaskGuard.TASK_NAME);
        assertThat(service.submit(id)).isEqualTo(first);
        assertThat(service.find(id)).isEqualTo(first);
    }

    @Test
    void shouldPersistCancellationBeforeAnyTaskExistsAndRejectLateGenericSubmit() {
        UUID id = UUID.randomUUID();
        var cancelled = service.cancel(id);
        assertThat(cancelled.cancellationRecorded()).isTrue();
        assertThat(cancelled.taskInstanceId()).isNull();
        assertThat(service.cancel(id)).isEqualTo(cancelled);
        assertThat(service.find(id)).isEqualTo(cancelled);
        expect(ErrorCode.TASK_DISPATCH_CANCELLED, () -> service.submit(id));
        expect(ErrorCode.TASK_DISPATCH_CANCELLED, () -> instances.create(guard.request(id)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_instance WHERE idempotency_key = ?", Integer.class,
                ReaderAdaptationTaskGuard.key(id))).isZero();
    }

    @Test
    void shouldCancelCreatedTaskAfterLostResponseAndKeepPermanentGuard() {
        UUID id = UUID.randomUUID();
        var submitted = service.submit(id);
        var cancelled = service.cancel(id);
        assertThat(cancelled.taskInstanceId()).isEqualTo(submitted.taskInstanceId());
        assertThat(cancelled.taskStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancellationRecorded()).isTrue();
        expect(ErrorCode.TASK_DISPATCH_CANCELLED, () -> service.submit(id));
        assertThat(service.cancel(id)).isEqualTo(cancelled);
    }

    @Test
    void shouldSerializeConcurrentCreateAndCancelWithoutOrphanTask() throws Exception {
        UUID id = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var created = pool.submit(() -> {
                readerIdentity();
                try {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return service.submit(id).taskInstanceId();
                } catch (SchedulerException exception) {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_DISPATCH_CANCELLED);
                    return null;
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            });
            var cancelled = pool.submit(() -> {
                readerIdentity();
                try {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return service.cancel(id);
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            });
            start.countDown();
            created.get(10, TimeUnit.SECONDS);
            assertThat(cancelled.get(10, TimeUnit.SECONDS).cancellationRecorded()).isTrue();
        }
        var result = service.find(id);
        assertThat(result.cancellationRecorded()).isTrue();
        assertThat(result.taskStatus()).isIn(null, "CANCELLED");
        expect(ErrorCode.TASK_DISPATCH_CANCELLED, () -> service.submit(id));
    }

    @Test
    void shouldRejectExtraParametersLabelsAndForeignBusinessIdentity() {
        UUID id = UUID.randomUUID();
        var canonical = guard.request(id);
        var extra = new CreateTaskRequest(canonical.taskName(), canonical.idempotencyKey(), canonical.businessType(), canonical.businessId(),
                null, canonical.priority(), Map.of("adaptationId", id.toString(), "text", "Untrusted content"), canonical.requiredNodeLabels());
        expect(ErrorCode.INVALID_REQUEST, () -> instances.create(extra));
        var noIsolation = new CreateTaskRequest(canonical.taskName(), canonical.idempotencyKey(), canonical.businessType(), canonical.businessId(),
                null, canonical.priority(), canonical.parameters(), Map.of());
        expect(ErrorCode.INVALID_REQUEST, () -> instances.create(noIsolation));
        var foldedName = new CreateTaskRequest(ReaderAdaptationTaskGuard.TASK_NAME.toUpperCase(java.util.Locale.ROOT),
                "nonreserved-" + id, canonical.businessType(), canonical.businessId(), null, canonical.priority(),
                canonical.parameters(), canonical.requiredNodeLabels());
        expect(ErrorCode.INVALID_REQUEST, () -> instances.create(foldedName));
        var request = new MockHttpServletRequest();
        request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE, "other-service");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        expect(ErrorCode.UNAUTHORIZED, () -> service.submit(id));
        expect(ErrorCode.UNAUTHORIZED, () -> service.cancel(id));
        expect(ErrorCode.UNAUTHORIZED, () -> service.find(id));
        expect(ErrorCode.UNAUTHORIZED, () -> instances.create(foldedName));
    }

    @Test
    void shouldNotTreatMissingTaskQueryAsCancellationBarrier() {
        UUID id = UUID.randomUUID();
        assertThat(service.find(id).taskInstanceId()).isNull();
        assertThat(service.find(id).cancellationRecorded()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_adaptation_dispatch_guard WHERE adaptation_id = ?", Integer.class, id.toString())).isZero();
        assertThat(service.submit(id).taskInstanceId()).isNotNull();
    }

    @Test
    void shouldAuthenticateActualHttpFilterBeforeAcceptingReaderIdentity() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        String path = "/api/v1/task-instances/reader-adaptations/" + UUID.randomUUID();
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-Task-Service-Id", "reader-service")
                .header("X-Task-Business-Token", "incorrect-fixture-token")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-Task-Service-Id", "other-service")
                .header("X-Task-Business-Token", "fixture-other-business-token")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-Task-Service-Id", "reader-service")
                .header("X-Task-Business-Token", "fixture-reader-business-token"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.taskStatus").value("QUEUED"));
        mvc.perform(get(path).header("X-Task-Service-Id", "reader-service")
                .header("X-Task-Business-Token", "fixture-reader-business-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancellationRecorded").value(false));
        mvc.perform(post(path + "/cancel").header("X-Task-Service-Id", "reader-service")
                .header("X-Task-Business-Token", "fixture-reader-business-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancellationRecorded").value(true));
    }

    @Test
    void shouldRejectForeignClientOnGenericDetailResultsAndCancelRoutes() throws Exception {
        UUID task = service.submit(UUID.randomUUID()).taskInstanceId();
        RequestContextHolder.resetRequestAttributes();
        String path = "/api/v1/task-instances/" + task;
        mvc.perform(get(path).header("X-Task-Service-Id", "other-service")
                .header("X-Task-Business-Token", "fixture-other-business-token")).andExpect(status().isUnauthorized());
        mvc.perform(get(path + "/results").header("X-Task-Service-Id", "other-service")
                .header("X-Task-Business-Token", "fixture-other-business-token")).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/cancel").header("X-Task-Service-Id", "other-service")
                .header("X-Task-Business-Token", "fixture-other-business-token")).andExpect(status().isUnauthorized());
        mvc.perform(get(path + "/results").header("X-Task-Service-Id", "reader-service")
                .header("X-Task-Business-Token", "fixture-reader-business-token")).andExpect(status().isOk());
    }

    @Test
    void shouldRejectHttpPayloadInsteadOfForwardingTextOrModelOptions() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        mvc.perform(post("/api/v1/task-instances/reader-adaptations/" + UUID.randomUUID())
                .header("X-Task-Service-Id", "reader-service").header("X-Task-Business-Token", "fixture-reader-business-token")
                .contentType("application/json").content("{\"intent\":\"Untrusted content\"}"))
                .andExpect(status().isBadRequest());
    }

    private static void readerIdentity() {
        var request = new MockHttpServletRequest();
        request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE, "reader-service");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void expect(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SchedulerException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}
