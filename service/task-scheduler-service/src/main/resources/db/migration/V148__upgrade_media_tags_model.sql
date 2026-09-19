-- 升级标签脚本默认模型，保留历史脚本包和任务快照。
UPDATE task_definition
SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (
    SELECT task_definition_id FROM task_step_definition
    WHERE script_package = 'media_generate_tags'
      AND script_version IN ('1.0.0', '1.1.0')
)
ORDER BY version DESC;

UPDATE task_step_definition
SET script_version = '1.2.0', updated_at = CURRENT_TIMESTAMP
WHERE script_package = 'media_generate_tags'
  AND script_version IN ('1.0.0', '1.1.0');
