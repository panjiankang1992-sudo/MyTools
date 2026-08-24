INSERT INTO execution_cluster (
    id, name, description, dispatch_strategy, max_concurrent_tasks, labels_json, enabled, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000011', 'reader-probe-orchestration',
    'Reader probe-term and child-task orchestration workers', 'LEAST_RUNNING', 2,
    '{"workload":"reader-probe","dsh.connector":"present"}', TRUE,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000378', 'reader_probe_search',
    'Generate frozen probe terms and aggregate a sharded Reader source-search child',
    'IMMEDIATE', 480, '00000000-0000-4000-8000-000000000011', NULL, NULL,
    'SINGLE_NODE', TRUE, 2, 'SKIP', 'IGNORE', '{}', '{}', 1,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000478', '00000000-0000-4000-8000-000000000378',
    'probe_search', 'Generate terms, execute the sharded child task and aggregate results', 'NORMAL',
    'reader_probe_search', '1.0.0', 'scripts/main.py', '[]', TRUE, 480, 'FAIL_TASK', 10, 1,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
