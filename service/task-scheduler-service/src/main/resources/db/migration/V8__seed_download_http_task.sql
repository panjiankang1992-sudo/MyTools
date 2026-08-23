INSERT INTO execution_cluster (
    id, name, description, dispatch_strategy, max_concurrent_tasks, labels_json, enabled, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000003', 'download', 'Download task workers',
    'LEAST_RUNNING', 4, '{"workload":"download"}', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000304', 'download_http_asset',
    'Download one bounded HTTP asset and verify its checksum', 'IMMEDIATE', 1800,
    '00000000-0000-4000-8000-000000000003', NULL, NULL, 'SINGLE_NODE', TRUE,
    4, 'SKIP', 'IGNORE', '{}', '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000404', '00000000-0000-4000-8000-000000000304',
    'download_asset', 'Stream and atomically publish one HTTP asset', 'NORMAL', 'download_http_asset',
    '1.0.0', 'scripts/main.py', '[]', TRUE, 1800, 'FAIL_TASK', 10, 3,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
