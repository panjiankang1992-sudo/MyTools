-- 用户批次共享任务自身的全天执行预算，避免包含排队和多媒体处理的批次被三十分钟提前取消。
UPDATE task_definition
SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT task_definition_id FROM task_step_definition
             WHERE script_package = 'download_resolve_x_user' AND script_version = '1.1.0');
UPDATE task_step_definition
SET script_version = '1.1.1', updated_at = CURRENT_TIMESTAMP
WHERE script_package = 'download_resolve_x_user' AND script_version = '1.1.0';
