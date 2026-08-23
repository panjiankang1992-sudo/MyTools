INSERT INTO execution_cluster (
    id, name, description, dispatch_strategy, max_concurrent_tasks, labels_json, enabled, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000004', 'messaging', 'Credential-isolated messaging workers',
    'LEAST_RUNNING', 8, '{"workload":"messaging"}', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000313', 'message_send_email',
    'Execute one server-side email delivery by opaque delivery identifier',
    'IMMEDIATE', 120, '00000000-0000-4000-8000-000000000004', NULL, NULL, 'SINGLE_NODE', TRUE,
    8, 'SKIP', 'IGNORE', '{}', '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000417', '00000000-0000-4000-8000-000000000313',
    'send_email', 'Invoke the credential-isolated Messaging Service email adapter', 'NORMAL',
    'message_send_email', '1.0.0', 'scripts/main.py', '[]', TRUE, 120, 'FAIL_TASK', 10, 3,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
