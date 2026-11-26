package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.model.*;
import com.yuyutian.mytools.task.scheduler.service.NewsTaskAccess;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NewsExecutionAuthorizationControllerTest {
    private JdbcTemplate jdbc;
    private NewsExecutionAuthorizationController controller;
    private final UUID task = UUID.randomUUID();
    private final UUID execution = UUID.randomUUID();
    private final UUID lease = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();

    @BeforeEach void setup() {
        var data = new JdbcDataSource(); data.setURL("jdbc:h2:mem:news-auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE task_instance(id VARCHAR(36), business_id VARCHAR(36), idempotency_key VARCHAR(128), status VARCHAR(32), cancel_requested_at TIMESTAMP, task_name VARCHAR(64), business_type VARCHAR(64))");
        jdbc.execute("CREATE TABLE task_execution(id VARCHAR(36), task_instance_id VARCHAR(36), node_id VARCHAR(36), fencing_token BIGINT, lease_token VARCHAR(36), lease_until TIMESTAMP, status VARCHAR(32))");
        jdbc.execute("CREATE TABLE executor_node(id VARCHAR(36), name VARCHAR(128))");
        jdbc.update("INSERT INTO executor_node VALUES ('node-id', 'node')");
        jdbc.update("INSERT INTO task_instance VALUES (?, ?, ?, 'RUNNING', NULL, 'news_generate_edition', 'NEWS_EDITION')", task.toString(), run.toString(), "news:" + run);
        jdbc.update("INSERT INTO task_execution VALUES (?, ?, 'node-id', 1, ?, ?, 'RUNNING')", execution.toString(), task.toString(), lease.toString(), Timestamp.from(Instant.now().plusSeconds(90)));
        controller = new NewsExecutionAuthorizationController(jdbc);
        identity("news-digest-service");
    }

    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void validLeaseReturnsOnlyCurrentScopeAndNoCacheHeaders() {
        var result = controller.authorize(execution, lease);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store, private");
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().taskId()).isEqualTo(task);
        assertThat(result.getBody().nodeId()).isEqualTo("node");
        assertThat(result.getBody().fencingToken()).isEqualTo(1);
    }

    @Test void otherServiceAndMissingIdentityCannotInspectLease() {
        for (String id : new String[]{"messaging-service", "legacy-business-client", ""}) {
            identity(id);
            assertThatThrownBy(() -> controller.authorize(execution, lease)).isInstanceOf(SchedulerException.class);
        }
    }

    @Test void expiredCancelledWrongTokenAndSupersededFenceAreRejected() {
        assertThatThrownBy(() -> controller.authorize(execution, UUID.randomUUID())).isInstanceOf(SchedulerException.class);
        jdbc.update("UPDATE task_execution SET lease_until = ?", Timestamp.from(Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> controller.authorize(execution, lease)).isInstanceOf(SchedulerException.class);
        jdbc.update("UPDATE task_execution SET lease_until = ?", Timestamp.from(Instant.now().plusSeconds(90)));
        jdbc.update("UPDATE task_instance SET cancel_requested_at = ?", Timestamp.from(Instant.now()));
        assertThatThrownBy(() -> controller.authorize(execution, lease)).isInstanceOf(SchedulerException.class);
        jdbc.update("UPDATE task_instance SET cancel_requested_at = NULL");
        jdbc.update("INSERT INTO task_execution VALUES (?, ?, 'node-id', 2, ?, ?, 'RUNNING')", UUID.randomUUID().toString(), task.toString(), UUID.randomUUID().toString(), Timestamp.from(Instant.now().plusSeconds(90)));
        assertThatThrownBy(() -> controller.authorize(execution, lease)).isInstanceOf(SchedulerException.class);
    }

    @Test void taskDomainIsRestrictedInBothDirectionsIncludingAliases() {
        var request = new CreateTaskRequest("news_generate_edition", "news:" + run, "NEWS_EDITION", run.toString(), null, 0, Map.of("runId", run.toString()), Map.of("news.node", "node"));
        assertThatCode(() -> NewsTaskAccess.create(request)).doesNotThrowAnyException();
        identity("messaging-service");
        assertThatThrownBy(() -> NewsTaskAccess.create(request)).isInstanceOf(SchedulerException.class);
        identity("news-digest-service");
        assertThatThrownBy(() -> NewsTaskAccess.create(new CreateTaskRequest("send_email", "email:1", "EMAIL", "1", null, 0, Map.of(), Map.of())))
                .isInstanceOf(SchedulerException.class);
        assertThatThrownBy(() -> NewsTaskAccess.create(new CreateTaskRequest("news_generate_edition", "news:" + run, "NEWS_EDITION", run.toString(), null, 0,
                Map.of("runId", run.toString(), "ownerId", 99), Map.of("news.node", "node")))).isInstanceOf(SchedulerException.class);
        var news = new TaskInstanceView(task, "NEWS_GENERATE_EDITION", "NEWS:" + run, null, "NEWS_EDITION", run.toString(), 0, Map.of(), Map.of(), TaskStatus.RUNNING, null, null, null);
        assertThatCode(() -> NewsTaskAccess.read(news)).doesNotThrowAnyException();
        identity("reader-service");
        assertThatThrownBy(() -> NewsTaskAccess.read(news)).isInstanceOf(SchedulerException.class);
    }

    @Test void regionJobsRequireDistinctBusinessAndIdempotencyScope() {
        var valid = new CreateTaskRequest("news_resolve_region", "news:region:" + run, "NEWS_REGION", run.toString(), null, 0, Map.of("jobId", run.toString()), Map.of("news.node", "node"));
        assertThatCode(() -> NewsTaskAccess.create(valid)).doesNotThrowAnyException();
        assertThatThrownBy(() -> NewsTaskAccess.create(new CreateTaskRequest("news_resolve_region", "news:" + run, "NEWS_EDITION", run.toString(), null, 0,
                Map.of("runId", run.toString()), Map.of("news.node", "node")))).isInstanceOf(SchedulerException.class);
        jdbc.update("UPDATE task_instance SET task_name = 'news_resolve_region', business_type = 'NEWS_REGION', idempotency_key = ?", "news:region:" + run);
        assertThat(controller.authorize(execution, lease).getBody().businessId()).isEqualTo(run.toString());
        jdbc.update("UPDATE task_instance SET business_type = 'NEWS_EDITION'");
        assertThatThrownBy(() -> controller.authorize(execution, lease)).isInstanceOf(SchedulerException.class);
    }

    @Test void weeklyTasksRequireTheirOwnDispatchScopeAndNewsIdentity() {
        var valid=new CreateTaskRequest("news_generate_weekly","news:weekly:"+run,"NEWS_WEEKLY",run.toString(),null,0,
                Map.of("jobId",run.toString()),Map.of("news.node","node"));
        assertThatCode(() -> NewsTaskAccess.create(valid)).doesNotThrowAnyException();
        identity("reader-service");
        assertThatThrownBy(() -> NewsTaskAccess.create(valid)).isInstanceOf(SchedulerException.class);
        identity("news-digest-service");
        assertThatThrownBy(() -> NewsTaskAccess.create(new CreateTaskRequest("news_generate_weekly","news:"+run,"NEWS_EDITION",
                run.toString(),null,0,Map.of("jobId",run.toString()),Map.of("news.node","node")))).isInstanceOf(SchedulerException.class);
        jdbc.update("UPDATE task_instance SET task_name='news_generate_weekly',business_type='NEWS_WEEKLY',idempotency_key=?","news:weekly:"+run);
        assertThat(controller.authorize(execution,lease).getBody().businessId()).isEqualTo(run.toString());
        jdbc.update("UPDATE task_instance SET business_type='NEWS_EDITION'");
        assertThatThrownBy(() -> controller.authorize(execution,lease)).isInstanceOf(SchedulerException.class);
    }

    private void identity(String id) {
        var request = new MockHttpServletRequest(); request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE, id);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
