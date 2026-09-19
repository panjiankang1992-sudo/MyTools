-- 多文件下载使用稳定来源序号，本地名称兼容存储 URI。
UPDATE task_step_definition
SET script_version='1.2.0', updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_remote_storage_object';

UPDATE task_step_definition
SET script_version='1.3.0', updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_pikpak_magnet';

UPDATE task_definition
SET parameter_schema=JSON_SET(parameter_schema, '$.properties.sourceIndex',
    JSON_OBJECT('type','integer','minimum',0,'maximum',9999)),
    version=version+1, updated_at=CURRENT_TIMESTAMP
WHERE name='download_remote_storage_object';

UPDATE task_definition
SET version=version+1, updated_at=CURRENT_TIMESTAMP
WHERE name='download_pikpak_magnet';
