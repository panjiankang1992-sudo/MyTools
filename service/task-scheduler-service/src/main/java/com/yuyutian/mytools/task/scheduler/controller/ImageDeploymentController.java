package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运维身份通过审计接口控制固定图片任务，不允许任意修改调度定义。 */
@RestController
@RequestMapping("/internal/v1/image-generation-deployment")
public class ImageDeploymentController {
    private final JdbcTemplate jdbc;

    /** 注入调度数据库访问组件。 */
    public ImageDeploymentController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 图片发布状态及调用者提供的审计标识。 */
    public record Deployment(boolean enabled, @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String auditId) { }

    /** 原子更新固定任务和集群，保留每次操作记录及既有任务。 */
    @PostMapping
    @Transactional
    public Map<String, Object> update(@Valid @RequestBody Deployment change, HttpServletRequest request) {
        // 过滤器已验证独立令牌，此处进一步限制为运维身份。
        if (!"task-operator-service".equals(request.getAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE))
                || change.auditId() == null) {
            throw new SchedulerException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Operator identity is required");
        }
        jdbc.update("UPDATE execution_cluster SET enabled=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND name=?",
                change.enabled(), "a13bb181-55e4-41d5-ab17-68ed4626123f", "image-generation");
        jdbc.update("UPDATE task_definition SET enabled=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND name=? AND enabled<>?",
                change.enabled(), "bc4024f1-2062-440f-aed1-a50b9b6f6650", "image_generate", change.enabled());
        jdbc.update("INSERT INTO image_deployment_audit(id,enabled,service_id,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",
                java.util.UUID.randomUUID().toString() + ":" + change.auditId(), change.enabled(), "task-operator-service");
        return Map.of("enabled", change.enabled(), "auditId", change.auditId());
    }
}
