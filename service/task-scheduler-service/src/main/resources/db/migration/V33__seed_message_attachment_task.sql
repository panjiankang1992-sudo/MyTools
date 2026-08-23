INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000321', 'message_download_attachment',
    'Create one Download Ingestion child task from an opaque message attachment job',
    'IMMEDIATE', 60, '00000000-0000-4000-8000-000000000004', NULL, NULL, 'SINGLE_NODE', TRUE,
    8, 'SKIP', 'IGNORE', '{}', '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000437', '00000000-0000-4000-8000-000000000321',
    'submit_download', 'Submit one attachment to the controlled download pipeline', 'NORMAL',
    'message_submit_attachment_download', '1.0.0', 'scripts/main.py', '[]', TRUE, 60,
    'FAIL_TASK', 10, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
