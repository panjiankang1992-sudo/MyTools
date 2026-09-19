-- 反推使用完整 JSON 结果，保留历史执行快照的旧版本。
UPDATE task_step_definition SET script_version = '1.1.2', updated_at = CURRENT_TIMESTAMP
WHERE id = '8327e385-fbe4-4e54-97c3-9b0c2921dd16' AND script_package = 'image_generate' AND script_version = '1.1.1';
UPDATE task_definition SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = 'bc4024f1-2062-440f-aed1-a50b9b6f6650' AND name = 'image_generate';
