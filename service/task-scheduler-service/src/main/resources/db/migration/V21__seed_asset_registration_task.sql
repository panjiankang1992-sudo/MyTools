INSERT INTO execution_cluster (
    id, name, description, dispatch_strategy, max_concurrent_tasks, labels_json, enabled, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000005', 'asset', 'Asset Registry integration workers',
    'LEAST_RUNNING', 8, '{"workload":"asset"}', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000314', 'asset_register_content',
    'Register one verified task output with the unified Asset Registry',
    'IMMEDIATE', 60, '00000000-0000-4000-8000-000000000005', NULL, NULL, 'SINGLE_NODE', TRUE,
    16, 'SKIP', 'IGNORE', '{}', '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000418', '00000000-0000-4000-8000-000000000314',
    'register_asset', 'Register verified content, source, and initial location', 'NORMAL',
    'asset_register_content', '1.0.0', 'scripts/main.py', '[]', TRUE, 60, 'FAIL_TASK', 10, 3,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000419', '00000000-0000-4000-8000-000000000309',
    'register_asset', 'Mirror the imported ebook into the unified Asset Registry', 'NORMAL',
    'asset_register_content', '1.0.0', 'scripts/main.py', '[]', TRUE, 60, 'IGNORE', 40, 3,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
