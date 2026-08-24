UPDATE task_definition
SET description = 'Migrate one frozen MyTools user set with target reconciliation evidence',
    timeout_seconds = 1800,
    parameter_schema = '{"type":"object","required":["migrationKey","dryRun"],"properties":{"migrationKey":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"dryRun":{"type":"boolean"},"snapshotHighWater":{"type":"integer","minimum":0}},"additionalProperties":false}',
    result_schema = '{"type":"object","required":["migrationKey","dryRun","exported","accepted","skipped","rejected","digestSha256","sourceItemCount","sourceDigestSha256","sourceHighWater"]}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000317'
  AND name = 'identity_migrate_users';

UPDATE task_step_definition
SET script_version = '1.1.0',
    description = 'Migrate frozen users through the audited Identity batch API',
    timeout_seconds = 1800,
    updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = '00000000-0000-4000-8000-000000000317'
  AND name = 'migrate_users';
