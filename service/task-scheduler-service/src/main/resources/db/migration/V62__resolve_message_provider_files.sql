UPDATE task_definition
SET description = 'Resolve provider file references and create one Download Ingestion child task by opaque job id',
    parameter_schema = '{"type":"object","required":["attachmentJobId"],"properties":{"attachmentJobId":{"type":"string","format":"uuid"}},"additionalProperties":false}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000321';

UPDATE task_step_definition
SET sequence_number = 20, updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000437';

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000525', '00000000-0000-4000-8000-000000000321',
    'resolve_provider_file', 'Resolve provider file reference inside the Messaging trust boundary', 'NORMAL',
    'message_resolve_provider_file', '1.0.0', 'scripts/main.py', '[]', TRUE, 60,
    'FAIL_TASK', 10, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
