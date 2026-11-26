-- 切换到媒体专用时间线，按实际媒体数量判断分页末尾，保留旧包用于回退。
UPDATE task_definition
SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT task_definition_id FROM task_step_definition
             WHERE script_package = 'download_resolve_x_user' AND script_version = '1.0.0');

UPDATE task_step_definition
SET script_version = '1.1.0', updated_at = CURRENT_TIMESTAMP
WHERE script_package = 'download_resolve_x_user' AND script_version = '1.0.0';
