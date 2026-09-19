-- 新任务引用新包，已运行任务继续使用原不可变快照。
UPDATE task_definition
SET parameter_schema = JSON_SET(parameter_schema,
    '$.properties.mode.enum', JSON_ARRAY('TEXT_TO_IMAGE', 'STYLE_REFERENCE', 'IMAGE_TO_IMAGE', 'IMAGE_TO_PROMPT'),
    '$.properties.resourceId.enum', JSON_ARRAY('krea2-local', 'sillytraven-remote', 'vision-local')),
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = 'bc4024f1-2062-440f-aed1-a50b9b6f6650' AND name = 'image_generate';
UPDATE task_step_definition SET script_version = '1.1.1', updated_at = CURRENT_TIMESTAMP
WHERE id = '8327e385-fbe4-4e54-97c3-9b0c2921dd16' AND script_package = 'image_generate';
