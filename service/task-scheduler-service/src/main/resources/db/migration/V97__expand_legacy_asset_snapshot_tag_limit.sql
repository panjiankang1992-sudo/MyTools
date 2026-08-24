UPDATE task_definition
SET version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = '00000000-0000-4000-8000-000000000393'
  AND name = 'legacy_asset_capture_snapshot';

UPDATE task_step_definition
SET script_version = '1.1.0',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = '00000000-0000-4000-8000-000000000493'
  AND script_package = 'legacy_asset_capture_snapshot';
