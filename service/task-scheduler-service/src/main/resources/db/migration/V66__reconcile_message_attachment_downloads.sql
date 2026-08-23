INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000408', 'message_reconcile_attachment_download',
    'Reconcile one owner-bound message attachment download to a terminal state',
    'IMMEDIATE', 1800, '00000000-0000-4000-8000-000000000004', NULL, NULL, 'SINGLE_NODE', TRUE,
    8, 'SKIP', 'IGNORE',
    '{"type":"object","required":["attachmentJobId"],"properties":{"attachmentJobId":{"type":"string","format":"uuid"}},"additionalProperties":false}',
    '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000529', '00000000-0000-4000-8000-000000000408',
    'reconcile_download', 'Poll the Messaging boundary until the attachment download is terminal',
    'NORMAL', 'message_reconcile_attachment_download', '1.0.0', 'scripts/main.py', '[]', TRUE,
    1800, 'FAIL_TASK', 10, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

UPDATE task_definition
SET description = 'Create one Download Ingestion child and an independent terminal reconciliation task',
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000321'
  AND name = 'message_download_attachment';

UPDATE task_step_definition
SET script_version = '1.1.0',
    description = 'Submit one attachment and create an idempotent terminal reconciliation child',
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000437'
  AND task_definition_id = '00000000-0000-4000-8000-000000000321'
  AND name = 'submit_download';
