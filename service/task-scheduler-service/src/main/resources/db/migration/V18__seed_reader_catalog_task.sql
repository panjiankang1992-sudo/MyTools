INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000311', 'reader_build_catalog',
    'Build and batch-persist a bounded catalog for a managed ebook artifact',
    'IMMEDIATE', 600, '00000000-0000-4000-8000-000000000002', NULL, NULL, 'SINGLE_NODE', TRUE,
    4, 'SKIP', 'IGNORE', '{}', '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000414', '00000000-0000-4000-8000-000000000311',
    'build_catalog', 'Build and persist a bounded format-aware ebook catalog', 'NORMAL',
    'reader_build_catalog', '1.0.0', 'scripts/main.py', '[]', TRUE, 600, 'FAIL_TASK', 10, 2,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000415', '00000000-0000-4000-8000-000000000309',
    'build_catalog', 'Build the catalog for the artifact published by the import step', 'NORMAL',
    'reader_build_catalog', '1.0.0', 'scripts/main.py', '[]', TRUE, 600, 'FAIL_TASK', 30, 2,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
