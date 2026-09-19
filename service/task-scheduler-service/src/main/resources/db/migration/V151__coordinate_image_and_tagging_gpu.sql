-- 新标签包保留人物优先提示词，只有显式开启 GPU 协调门禁才启用互斥协议。
UPDATE task_definition SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT task_definition_id FROM task_step_definition
             WHERE script_package = 'media_generate_tags' AND script_version = '1.3.0')
ORDER BY version DESC;
UPDATE task_step_definition SET script_version = '1.4.0', updated_at = CURRENT_TIMESTAMP
WHERE script_package = 'media_generate_tags' AND script_version = '1.3.0';
