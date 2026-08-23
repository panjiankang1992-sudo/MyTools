INSERT INTO execution_cluster (
    id, name, description, dispatch_strategy, max_concurrent_tasks, labels_json, enabled, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000001', 'media', 'Media intelligence workers',
    'LEAST_RUNNING', 4, '{"workload":"media"}', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000101', 'media_generate_tags',
    'Generate versioned media tags as a migration sidecar', 'IMMEDIATE', 180,
    '00000000-0000-4000-8000-000000000001', NULL, NULL, 'SINGLE_NODE', TRUE, 4,
    'SKIP', 'IGNORE', '{}', '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000101',
    'generate_tags', 'Generate sidecar tag result', 'NORMAL', 'media_generate_tags', '1.0.0',
    'scripts/main.py', '[]', TRUE, 180, 'FAIL_TASK', 10, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
