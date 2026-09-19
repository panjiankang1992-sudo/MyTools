package com.yuyutian.mytools.task.scheduler.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 由服务自身按受控部署配置启停已发布改编模板，运维脚本不直接修改调度数据库。 */
@Component
@ConditionalOnProperty(name = "task.reader-adaptation-deployment.configured", havingValue = "true")
public class AdaptationDeploymentInitializer implements ApplicationRunner {
    private static final String CLUSTER = "c92ac466-14e2-48b7-b551-bd7a676aa0a7";
    private static final String DEFINITION = "d9c1c5ef-4656-4770-9976-955e93906e93";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Environment environment;

    /** 仅使用 Scheduler 自有数据库账号和事务，配置不含用户内容或模型凭据。 */
    public AdaptationDeploymentInitializer(JdbcTemplate jdbc, PlatformTransactionManager manager, Environment environment) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.environment = environment;
    }

    /** 验证不可变模板身份后原子启停专用集群，重复启动不会改写既有启用审计。 */
    @Override
    public void run(ApplicationArguments arguments) {
        String audit = environment.getProperty("task.reader-adaptation-deployment.audit-id", "");
        boolean enabled = environment.getProperty("task.reader-adaptation-deployment.active", Boolean.class, false);
        if (!audit.matches("[A-Za-z0-9_.-]{1,100}") || enabled
                && !environment.getProperty("task.workload-authorization.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Adaptation deployment prerequisites are invalid");
        }
        transaction.executeWithoutResult(status -> {
            var clusters = jdbc.queryForList("SELECT name,enabled FROM execution_cluster WHERE id=? FOR UPDATE", CLUSTER);
            var definitions = jdbc.queryForList("SELECT name,cluster_id,enabled FROM task_definition WHERE id=? FOR UPDATE", DEFINITION);
            if (clusters.size() != 1 || definitions.size() != 1
                    || !"reader-adaptation".equals(clusters.getFirst().get("name"))
                    || !"reader_adapt_novel_chapter".equals(definitions.getFirst().get("name"))
                    || !CLUSTER.equals(definitions.getFirst().get("cluster_id"))) {
                throw new IllegalStateException("Adaptation deployment template is invalid");
            }
            Long steps = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM task_step_definition WHERE task_definition_id=?
                    AND script_package=? AND script_version=? AND entrypoint=? AND enabled=TRUE
                    """, Long.class, DEFINITION, "reader_adapt_novel_chapter", "1.0.0", "scripts/main.py");
            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM task_step_definition WHERE task_definition_id=?", Long.class, DEFINITION);
            if (!Long.valueOf(1).equals(steps) || !Long.valueOf(1).equals(total)) {
                throw new IllegalStateException("Adaptation deployment package is invalid");
            }
            // 条件更新使相同配置幂等；配置变更保留明确的部署审计标识。
            String description = "Controlled adaptation deployment; audit=" + audit;
            jdbc.update("UPDATE execution_cluster SET enabled=?,description=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND enabled<>?",
                    enabled, description, CLUSTER, enabled);
            jdbc.update("UPDATE task_definition SET enabled=?,description=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND enabled<>?",
                    enabled, description, DEFINITION, enabled);
        });
    }
}
