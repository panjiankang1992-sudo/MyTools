UPDATE task_definition
SET description = 'Migrate one frozen high-water MsgService history snapshot with source evidence',
    result_schema = '{"type":"object","required":["migrationKey","dryRun","exported","accepted","skipped","rejected","digestSha256","sourceItemCount","sourceDigestSha256","sourceHighWater"]}',
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE name = 'message_migrate_history';

UPDATE task_step_definition
SET script_version = '1.1.0',
    description = 'Migrate frozen sanitized message pages and verify source evidence remains stable',
    updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = (
    SELECT id FROM task_definition WHERE name = 'message_migrate_history'
)
  AND name = 'migrate_history';
