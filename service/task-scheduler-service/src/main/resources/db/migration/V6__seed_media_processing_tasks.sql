INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000301', 'media_probe', 'Probe media metadata with ffprobe',
    'IMMEDIATE', 30, '00000000-0000-4000-8000-000000000001', NULL, NULL, 'SINGLE_NODE', TRUE,
    8, 'SKIP', 'IGNORE', '{}', '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000401', '00000000-0000-4000-8000-000000000301',
    'probe', 'Run bounded ffprobe', 'NORMAL', 'media_probe', '1.0.0', 'scripts/main.py', '[]', TRUE,
    30, 'FAIL_TASK', 10, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000302', 'media_generate_thumbnail',
    'Generate a media thumbnail in executor staging', 'IMMEDIATE', 30,
    '00000000-0000-4000-8000-000000000001', NULL, NULL, 'SINGLE_NODE', TRUE,
    4, 'SKIP', 'IGNORE', '{}', '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000402', '00000000-0000-4000-8000-000000000302',
    'generate_thumbnail', 'Generate staged JPEG thumbnail', 'NORMAL', 'media_generate_thumbnail', '1.0.0',
    'scripts/main.py', '[]', TRUE, 30, 'FAIL_TASK', 10, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
