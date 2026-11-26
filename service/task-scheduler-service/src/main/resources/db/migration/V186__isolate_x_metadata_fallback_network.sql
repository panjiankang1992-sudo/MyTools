-- 公开帖子元数据采用独立直连和有界网络回退，保留旧包以便回退。
UPDATE task_definition
SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT task_definition_id FROM task_step_definition
             WHERE script_package = 'download_resolve_x_post' AND script_version = '1.0.0');

UPDATE task_step_definition
SET script_version = '1.0.1', updated_at = CURRENT_TIMESTAMP
WHERE script_package = 'download_resolve_x_post' AND script_version = '1.0.0';
