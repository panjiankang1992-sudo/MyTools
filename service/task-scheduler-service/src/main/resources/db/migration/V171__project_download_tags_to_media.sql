-- 已生成的下载标签必须同步媒体库，保持新下载与图片标签展示一致。
UPDATE task_definition SET version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE id IN (SELECT task_definition_id FROM task_step_definition WHERE script_package='download_record_tags' AND script_version='1.0.0');
UPDATE task_step_definition SET script_version='1.1.0',timeout_seconds=90,updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_record_tags' AND script_version='1.0.0';
