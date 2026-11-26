package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 通过运维身份启停固定的手动新闻任务，不修改任何日报定义或定时器。 */
@RestController
@RequestMapping("/internal/v1/news-weekly-deployment")
public class NewsWeeklyDeploymentController {
    private static final String DEFINITION = "945dd28a-fc94-4a06-aeb1-b8cd78907fbe";
    private final JdbcTemplate jdbc;

    /** 注入调度配置访问组件。 */
    public NewsWeeklyDeploymentController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 固定任务启停参数与幂等审计标识。 */
    public record Deployment(boolean enabled, @NotNull @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String auditId) { }

    /** 串行处理固定任务的幂等运维操作，拒绝同一审计标识改变意图。 */
    @PostMapping
    @Transactional
    public Map<String, Object> update(@Valid @RequestBody Deployment change, HttpServletRequest request) {
        if (!"task-operator-service".equals(request.getAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE))) {
            throw new SchedulerException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Operator identity is required");
        }
        // 固定行锁同时序列化启停与幂等检查，避免重复请求插入两次审计。
        jdbc.queryForObject("SELECT id FROM task_definition WHERE id=? AND name=? FOR UPDATE", String.class,
                DEFINITION, "news_generate_weekly");
        var old = jdbc.queryForList("SELECT enabled FROM news_weekly_deployment_audit WHERE id=?", Boolean.class, change.auditId());
        if (!old.isEmpty()) {
            if (old.getFirst() != change.enabled()) {
                throw new SchedulerException(ErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT, "Audit identity conflicts");
            }
            return Map.of("enabled", change.enabled(), "auditId", change.auditId(), "reused", true);
        }
        // 只打开共享新闻集群；回退时不关闭可能承载其他已授权任务的集群。
        if (change.enabled()) {
            jdbc.update("UPDATE execution_cluster SET enabled=?,updated_at=CURRENT_TIMESTAMP WHERE name=?",
                    true, "news");
        }
        jdbc.update("UPDATE task_definition SET enabled=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND enabled<>?",
                change.enabled(), DEFINITION, change.enabled());
        jdbc.update("INSERT INTO news_weekly_deployment_audit(id,enabled,service_id,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",
                change.auditId(), change.enabled(), "task-operator-service");
        return Map.of("enabled", change.enabled(), "auditId", change.auditId(), "reused", false);
    }
}
