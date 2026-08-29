-- X 用户批量任务为每个帖子分配独立结果索引段，避免跨帖子媒体冲突。
UPDATE task_definition
SET parameter_schema = REPLACE(parameter_schema, '"properties":{',
        '"properties":{"sourceIndexOffset":{"type":"integer","minimum":0,"maximum":999900},'),
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_x_post';
