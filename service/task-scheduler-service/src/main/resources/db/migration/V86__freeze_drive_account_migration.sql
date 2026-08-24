UPDATE task_definition
SET parameter_schema = '{"type":"object","required":["migrationKey","dryRun"],"properties":{"migrationKey":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"dryRun":{"type":"boolean"},"sourceHighWater":{"type":"object","required":["DRIVE","WEBDAV"],"properties":{"DRIVE":{"type":"integer","minimum":0},"WEBDAV":{"type":"integer","minimum":0}},"additionalProperties":false}},"additionalProperties":false}',
    result_schema = '{"type":"object","required":["migrationKey","dryRun","exported","accepted","skipped","rejected","digestSha256","sourceHighWater"]}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000316'
  AND name = 'drive_migrate_legacy_accounts';

UPDATE task_step_definition
SET script_version = '1.1.0', updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000427'
  AND script_package = 'drive_migrate_legacy_accounts';
