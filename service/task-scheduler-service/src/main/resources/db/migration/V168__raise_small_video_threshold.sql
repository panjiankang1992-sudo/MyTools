-- 使用不可变的新脚本版本，将小视频阈值统一提高到 100 MiB。
UPDATE task_step_definition SET script_version='1.2.0',updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_publish_file' AND script_version IN ('1.0.0','1.1.0');
UPDATE task_step_definition SET script_version='1.1.0',updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_storage_object' AND script_version='1.0.0';
UPDATE task_definition SET version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE id IN (SELECT task_definition_id FROM task_step_definition WHERE
(script_package='download_publish_file' AND script_version='1.2.0') OR
(script_package='download_storage_object' AND script_version='1.1.0'));
