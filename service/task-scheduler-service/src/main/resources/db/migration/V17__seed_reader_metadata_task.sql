INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000310', 'reader_extract_metadata',
    'Extract deterministic metadata from a managed ebook artifact',
    'IMMEDIATE', 600, '00000000-0000-4000-8000-000000000002', NULL, NULL, 'SINGLE_NODE', TRUE,
    4, 'SKIP', 'IGNORE', '{}', '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000412', '00000000-0000-4000-8000-000000000310',
    'extract_metadata', 'Download a managed ebook and extract bounded deterministic metadata', 'NORMAL',
    'reader_extract_metadata', '1.0.0', 'scripts/main.py', '[]', TRUE, 600, 'FAIL_TASK', 10, 2,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000413', '00000000-0000-4000-8000-000000000309',
    'extract_metadata', 'Extract metadata from the artifact published by the preceding import step', 'NORMAL',
    'reader_extract_metadata', '1.0.0', 'scripts/main.py', '[]', TRUE, 600, 'FAIL_TASK', 20, 2,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
