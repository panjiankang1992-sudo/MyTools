-- 兼容本机视觉模型将完整结构化结果放入 thinking 字段；保留不可变旧包。
UPDATE task_step_definition SET script_version='1.1.1' WHERE script_package='video_generate' AND script_version='1.1.0' AND task_definition_id=(SELECT id FROM task_definition WHERE name='video_generate');
