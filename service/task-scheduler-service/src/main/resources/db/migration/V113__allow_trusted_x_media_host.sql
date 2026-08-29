-- X 媒体解析器可向通用下载任务传递固定 twimg 域信任边界。
UPDATE task_definition
SET parameter_schema = REPLACE(parameter_schema, '"properties":{',
        '"properties":{"trustedHostSuffix":{"type":"string","enum":[".twimg.com"]},'),
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_http_asset';
