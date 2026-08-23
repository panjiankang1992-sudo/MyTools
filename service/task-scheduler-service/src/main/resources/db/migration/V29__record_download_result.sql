UPDATE task_step_definition
SET failure_policy = 'FAIL_TASK', updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = '00000000-0000-4000-8000-000000000304'
  AND name = 'register_asset';

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000430', '00000000-0000-4000-8000-000000000304',
    'record_result', 'Record verified item and asset in Download Ingestion', 'NORMAL',
    'download_record_result', '1.0.0', 'scripts/main.py', '[]', TRUE, 30, 'FAIL_TASK', 30, 3,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
