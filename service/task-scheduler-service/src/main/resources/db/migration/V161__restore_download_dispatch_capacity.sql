-- 恢复验收窗口遗留的下载暂停；只修复启用但容量为零的下载定义，保留其他工作负载。
UPDATE task_definition
SET max_concurrency = CASE WHEN name = 'download_http_asset' THEN 8 ELSE 4 END,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE enabled = TRUE AND max_concurrency = 0
  AND name IN ('download_http_asset', 'download_message_attachment',
               'download_remote_storage_object', 'download_storage_object');
