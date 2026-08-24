UPDATE task_definition
SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000392'
  AND name = 'asset_migrate_legacy_mappings';

UPDATE task_step_definition
SET script_version = '1.1.0', updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000492'
  AND script_package = 'asset_migrate_legacy_mappings';
