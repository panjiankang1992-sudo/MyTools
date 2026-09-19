-- 本地路径保留 Unicode，并以字节限制名称；补跑使用新脚本版本和独立幂等键。
UPDATE task_step_definition
SET script_version='1.1.0', updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_remote_storage_object';

UPDATE task_step_definition
SET script_version='1.2.0', updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_pikpak_magnet';

UPDATE task_definition
SET parameter_schema=JSON_SET(parameter_schema, '$.properties.recoveryAttempt',
    JSON_OBJECT('type','integer','minimum',1,'maximum',10)),
    version=version+1, updated_at=CURRENT_TIMESTAMP
WHERE name='download_pikpak_magnet';

UPDATE task_definition
SET version=version+1, updated_at=CURRENT_TIMESTAMP
WHERE name='download_remote_storage_object';
