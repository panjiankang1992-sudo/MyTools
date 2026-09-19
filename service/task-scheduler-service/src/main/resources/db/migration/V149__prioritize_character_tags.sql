-- 人物特征优先的标签提示词使用独立脚本版本，保留历史任务快照。
UPDATE task_definition
SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (
    SELECT task_definition_id FROM task_step_definition
    WHERE script_package = 'media_generate_tags'
      AND script_version IN ('1.0.0', '1.1.0', '1.2.0')
)
ORDER BY version DESC;

UPDATE task_step_definition
SET script_version = '1.3.0', updated_at = CURRENT_TIMESTAMP
WHERE script_package = 'media_generate_tags'
  AND script_version IN ('1.0.0', '1.1.0', '1.2.0');
