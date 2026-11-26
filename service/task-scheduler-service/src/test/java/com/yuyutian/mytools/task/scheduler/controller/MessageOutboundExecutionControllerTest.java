package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.model.*;
import com.yuyutian.mytools.task.scheduler.service.MessageOutboundTaskAccess;
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

class MessageOutboundExecutionControllerTest {
    private JdbcTemplate jdbc;
    private MessageOutboundExecutionController controller;
    private final UUID task = UUID.randomUUID();
    private final UUID execution = UUID.randomUUID();
    private final UUID lease = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();

    @BeforeEach void setup() {
        var data = new JdbcDataSource(); data.setURL("jdbc:h2:mem:outbound-auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE task_instance(id VARCHAR(36), business_id VARCHAR(36), idempotency_key VARCHAR(128), status VARCHAR(32), cancel_requested_at TIMESTAMP, task_name VARCHAR(64), business_type VARCHAR(64))");
        jdbc.execute("CREATE TABLE task_execution(id VARCHAR(36), task_instance_id VARCHAR(36), node_id VARCHAR(36), fencing_token BIGINT, lease_token VARCHAR(36), lease_until TIMESTAMP, status VARCHAR(32))");
        jdbc.execute("CREATE TABLE executor_node(id VARCHAR(36), name VARCHAR(128))");
        jdbc.update("INSERT INTO executor_node VALUES ('node-id', 'node')");
        jdbc.update("INSERT INTO task_instance VALUES (?, ?, ?, 'RUNNING', NULL, 'message_send_channel_message', 'MESSAGE_OUTBOUND')", task.toString(), run.toString(), "message-outbound:" + run + ":1");
        jdbc.update("INSERT INTO task_execution VALUES (?, ?, 'node-id', 1, ?, ?, 'RUNNING')", execution.toString(), task.toString(), lease.toString(), Timestamp.from(Instant.now().plusSeconds(90)));
        controller = new MessageOutboundExecutionController(jdbc);
        identity("messaging-service");
    }

    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void validLeaseReturnsOnlyCurrentScopeAndNoCacheHeaders() {
        var result = controller.authorize(execution, lease);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store, private");
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().taskId()).isEqualTo(task);
        assertThat(result.getBody().fencingToken()).isEqualTo(1);
    }

    @Test void otherServiceAndMissingIdentityCannotInspectLease() {
        for (String id : new String[]{"news-digest-service", "legacy-business-client", ""}) {
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

    @Test void onlyMessagingCanCreateTheExactVersionedContract() {
        var request = new CreateTaskRequest("message_send_channel_message", "message-outbound:" + run + ":1", "MESSAGE_OUTBOUND", run.toString(), null, 0, Map.of("deliveryId", run.toString(), "generation", 1), Map.of());
        assertThatCode(() -> MessageOutboundTaskAccess.create(request)).doesNotThrowAnyException();
        identity("news-digest-service");
        assertThatThrownBy(() -> MessageOutboundTaskAccess.create(request)).isInstanceOf(SchedulerException.class);
        identity("messaging-service");
        assertThatThrownBy(() -> MessageOutboundTaskAccess.create(new CreateTaskRequest("message_send_channel_message", "message-outbound:" + run + ":1", "MESSAGE_OUTBOUND", run.toString(), null, 0, Map.of("deliveryId", run.toString(), "generation", 1, "recipient", "forbidden"), Map.of())))
                .isInstanceOf(SchedulerException.class);
        assertThatThrownBy(() -> MessageOutboundTaskAccess.create(new CreateTaskRequest("message_send_channel_message", "message-outbound:" + run + ":1", "MESSAGE_OUTBOUND", run.toString(), null, 0, Map.of("deliveryId", run.toString(), "generation", 1.1), Map.of())))
                .isInstanceOf(SchedulerException.class);
    }

    private void identity(String id) {
        var request = new MockHttpServletRequest(); request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE, id);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
