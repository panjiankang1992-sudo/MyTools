package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import com.yuyutian.mytools.task.scheduler.config.TaskSecurityProperties;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开 PDF 任务域守卫的双向限制测试。
 *
 * 关键点：不仅其他身份不能碰公开任务，公开身份也不能碰其他任务；
 * 认证关闭或未配置该身份时必须失败关闭，不能退化成放行。
 */
class PublicPdfTaskAccessTest {

    private static final String PUBLIC_TASK = PublicPdfTaskAccess.TASK_NAME;
    private static final String PUBLIC_KEY = PublicPdfTaskAccess.IDEMPOTENCY_PREFIX + "123e4567-e89b-12d3-a456-426614174000";
    private static final String PRIVATE_TASK = "pdf_process";
    private static final String PRIVATE_KEY = "pdf:123e4567-e89b-12d3-a456-426614174000";

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 构造启用认证并登记公开身份的配置。 */
    private static TaskSecurityProperties secured() {
        return new TaskSecurityProperties(true, "internal-token", "business-token",
                Map.of("task-executor-service", "executor-token"),
                Map.of("public-pdf-service", "public-token", "pdf-service", "pdf-token"));
    }

    /** 把已认证服务身份写入当前请求上下文。 */
    private static void authenticateAs(String serviceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (serviceId != null) {
            request.setAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE, serviceId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static CreateTaskRequest request(String taskName, String idempotencyKey) {
        return new CreateTaskRequest(taskName, idempotencyKey, "PUBLIC_PDF_TOOL", "business-1", null, 40,
                Map.of("workId", "business-1"), Map.of());
    }

    private static TaskInstanceView view(String taskName, String idempotencyKey) {
        return new TaskInstanceView(java.util.UUID.randomUUID(), taskName, idempotencyKey, null,
                "PUBLIC_PDF_TOOL", "business-1", 40, Map.of(), Map.of(), null, null, null, null);
    }

    @Test
    @DisplayName("domain detection covers task name and idempotency prefix case-insensitively")
    void domainDetectionIsCaseInsensitive() {
        assertTrue(PublicPdfTaskAccess.isPublicDomain(PUBLIC_TASK, null));
        assertTrue(PublicPdfTaskAccess.isPublicDomain(PUBLIC_TASK.toUpperCase(java.util.Locale.ROOT), null));
        assertTrue(PublicPdfTaskAccess.isPublicDomain(null, PUBLIC_KEY));
        assertTrue(PublicPdfTaskAccess.isPublicDomain(null, PUBLIC_KEY.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(PublicPdfTaskAccess.isPublicDomain(PRIVATE_TASK, PRIVATE_KEY));
        assertFalse(PublicPdfTaskAccess.isPublicDomain(null, null));
        // 前缀相似但不同域的键不能被误判为公开任务。
        assertFalse(PublicPdfTaskAccess.isPublicDomain(null, "public-pdfx:1"));
    }

    @Test
    @DisplayName("only the configured public identity may create public PDF tasks")
    void publicCreateRequiresPublicIdentity() {
        PublicPdfTaskAccess guard = new PublicPdfTaskAccess(secured());

        authenticateAs("public-pdf-service");
        guard.requireCreate(request(PUBLIC_TASK, PUBLIC_KEY));

        authenticateAs("pdf-service");
        SchedulerException wrongClient = assertThrows(SchedulerException.class,
                () -> guard.requireCreate(request(PUBLIC_TASK, PUBLIC_KEY)));
        assertEquals(ErrorCode.UNAUTHORIZED, wrongClient.errorCode());

        authenticateAs(null);
        assertThrows(SchedulerException.class, () -> guard.requireCreate(request(PUBLIC_TASK, PUBLIC_KEY)));
    }

    @Test
    @DisplayName("the public identity may not create tasks outside its own domain")
    void publicIdentityCannotCreatePrivateTasks() {
        PublicPdfTaskAccess guard = new PublicPdfTaskAccess(secured());
        authenticateAs("public-pdf-service");

        SchedulerException forbidden = assertThrows(SchedulerException.class,
                () -> guard.requireCreate(request(PRIVATE_TASK, PRIVATE_KEY)));
        assertEquals(ErrorCode.TASK_DOMAIN_FORBIDDEN, forbidden.errorCode());

        // 其他身份创建自己的任务不受影响，既有行为保持不变。
        authenticateAs("pdf-service");
        guard.requireCreate(request(PRIVATE_TASK, PRIVATE_KEY));
    }

    @Test
    @DisplayName("read, cancel and result access share the same two-way restriction")
    void accessGuardIsBidirectional() {
        PublicPdfTaskAccess guard = new PublicPdfTaskAccess(secured());

        authenticateAs("public-pdf-service");
        guard.requireAccess(view(PUBLIC_TASK, PUBLIC_KEY));
        assertEquals(ErrorCode.TASK_DOMAIN_FORBIDDEN,
                assertThrows(SchedulerException.class, () -> guard.requireAccess(view(PRIVATE_TASK, PRIVATE_KEY))).errorCode());

        authenticateAs("pdf-service");
        guard.requireAccess(view(PRIVATE_TASK, PRIVATE_KEY));
        assertEquals(ErrorCode.UNAUTHORIZED,
                assertThrows(SchedulerException.class, () -> guard.requireAccess(view(PUBLIC_TASK, PUBLIC_KEY))).errorCode());

        // 空任务不参与判定，与既有守卫一致。
        guard.requireAccess(null);
    }

    @Test
    @DisplayName("authentication disabled or identity unconfigured fails closed for public tasks")
    void unconfiguredIdentityFailsClosed() {
        TaskSecurityProperties disabled = new TaskSecurityProperties(false, "internal-token", "business-token",
                Map.of(), Map.of("pdf-service", "pdf-token"));
        PublicPdfTaskAccess guard = new PublicPdfTaskAccess(disabled);
        authenticateAs("public-pdf-service");

        assertEquals(ErrorCode.UNAUTHORIZED,
                assertThrows(SchedulerException.class, () -> guard.requireCreate(request(PUBLIC_TASK, PUBLIC_KEY))).errorCode());
        // 非公开任务在认证关闭时不受影响。
        guard.requireCreate(request(PRIVATE_TASK, PRIVATE_KEY));
    }
}
