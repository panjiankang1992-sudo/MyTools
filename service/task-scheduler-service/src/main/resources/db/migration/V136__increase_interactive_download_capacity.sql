UPDATE execution_cluster
SET max_concurrent_tasks = GREATEST(max_concurrent_tasks, 8),
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download';

UPDATE task_definition
SET max_concurrency = GREATEST(max_concurrency, 8),
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_http_asset';
