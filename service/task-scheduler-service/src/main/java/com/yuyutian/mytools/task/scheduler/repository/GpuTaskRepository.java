package com.yuyutian.mytools.task.scheduler.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 单执行节点的视频独占准入，避免短任务进入进程后耗尽显存锁等待时间。
 */
@Repository
public class GpuTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 GPU 任务准入仓储。
     * @param jdbcTemplate 数据库访问模板。
     */
    public GpuTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 检查节点上的视频互斥；调用者必须已锁定执行节点行。
     * @param taskId 候选任务标识。
     * @param nodeId 已被当前领取事务锁定的执行节点。
     * @return 是否允许派发。
     */
    public boolean canDispatch(UUID taskId, UUID nodeId) {
        String definitionId = jdbcTemplate.queryForObject(
                "SELECT task_definition_id FROM task_instance WHERE id = ?", String.class, taskId.toString());
        List<String> requested = packages(UUID.fromString(definitionId));
        if (requested.isEmpty()) {
            // CPU 与网络任务不受 GPU 视频窗口阻塞。
            return true;
        }
        List<String> running = jdbcTemplate.queryForList("""
                SELECT DISTINCT ts.script_package
                FROM task_execution te
                JOIN task_instance ti ON ti.id = te.task_instance_id
                JOIN task_step_definition ts ON ts.task_definition_id = ti.task_definition_id
                WHERE te.node_id = ? AND te.status = 'RUNNING' AND ts.enabled = TRUE
                  AND ts.script_package IN ('image_generate', 'media_generate_tags', 'video_generate')
                """, String.class, nodeId.toString());
        // 原有图片与标签互斥继续由文件锁承担；视频前后不得派发另一类 GPU 消费者。
        return requested.contains("video_generate") ? running.isEmpty() : !running.contains("video_generate");
    }

    private List<String> packages(UUID definitionId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT script_package FROM task_step_definition
                WHERE task_definition_id = ? AND enabled = TRUE
                  AND script_package IN ('image_generate', 'media_generate_tags', 'video_generate')
                """, String.class, definitionId.toString());
    }
}
