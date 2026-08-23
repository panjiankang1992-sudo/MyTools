INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000393', 'legacy_asset_capture_snapshot',
    'Materialize local_file through one consistent read-only source transaction',
    'IMMEDIATE', 3600, '00000000-0000-4000-8000-000000000005', NULL, NULL, 'SINGLE_NODE', TRUE,
    1, 'SKIP', 'IGNORE',
    '{"type":"object","required":["snapshotId"],"properties":{"snapshotId":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"ownerId":{"type":"integer","minimum":0}},"additionalProperties":false}',
    '{"type":"object","required":["snapshotId","ownerId","highWaterId","captured","rejected","digestSha256"]}',
    1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000493', '00000000-0000-4000-8000-000000000393',
    'capture_snapshot', 'Capture and atomically seal one consistent local_file snapshot', 'NORMAL',
    'legacy_asset_capture_snapshot', '1.0.0', 'scripts/main.py', '[]', TRUE, 3600,
    'FAIL_TASK', 10, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
