-- 只切换现有下载发布步骤到新不可变包，避免编码后的目标文件名超过文件系统上限。
UPDATE task_definition
SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT task_definition_id FROM task_step_definition
             WHERE script_package = 'download_publish_file' AND script_version = '1.2.0');

UPDATE task_step_definition
SET script_version = '1.2.1', updated_at = CURRENT_TIMESTAMP
WHERE script_package = 'download_publish_file' AND script_version = '1.2.0';
