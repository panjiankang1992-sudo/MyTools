UPDATE task_definition
SET parameter_schema = REPLACE(parameter_schema, '"properties":{',
        '"properties":{"resourceUsername":{"type":"string","pattern":"^[A-Za-z0-9_]{1,64}$"},'),
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name IN ('download_message_url_batch', 'download_x_post', 'download_web_archive',
               'download_local_import', 'download_storage_object');

UPDATE task_definition
SET parameter_schema = '{"type":"object","properties":{"resourceUsername":{"type":"string","pattern":"^[A-Za-z0-9_]{1,64}$"}},"additionalProperties":true}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_http_asset';
