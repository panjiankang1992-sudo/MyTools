-- 远端对象的可信 MIME 需由资产登记脚本消费，避免默认二进制类型导致重放冲突。
UPDATE task_step_definition
SET script_version='1.1.0', updated_at=CURRENT_TIMESTAMP
WHERE script_package='asset_register_content'
  AND task_definition_id IN (
    SELECT id FROM task_definition WHERE name='download_remote_storage_object'
  );

UPDATE task_definition
SET version=version+1, updated_at=CURRENT_TIMESTAMP
WHERE name='download_remote_storage_object';
