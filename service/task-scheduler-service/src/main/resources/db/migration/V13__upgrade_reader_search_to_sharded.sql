UPDATE task_definition
SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP
WHERE name = 'reader_source_search' AND version = 1;

INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000306', 'reader_source_search',
    'Search deterministic shards of enabled book sources across Reader Runtime nodes', 'IMMEDIATE', 300,
    '00000000-0000-4000-8000-000000000002', NULL, NULL, 'MULTI_NODE_SHARD', TRUE,
    4, 'SKIP', 'IGNORE', '{}', '{}', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000408', '00000000-0000-4000-8000-000000000306',
    'search_sources', 'Synchronize and search the assigned source shard', 'NORMAL', 'reader_source_search',
    '1.1.0', 'scripts/main.py', '[]', TRUE, 300, 'FAIL_TASK', 10, 2,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
