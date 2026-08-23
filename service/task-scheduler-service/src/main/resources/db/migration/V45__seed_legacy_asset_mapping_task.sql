INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000392', 'asset_migrate_legacy_mappings',
    'Dry-run or migrate normalized legacy asset identities into Asset Registry',
    'IMMEDIATE', 1800, '00000000-0000-4000-8000-000000000005', NULL, NULL, 'SINGLE_NODE', TRUE,
    1, 'SKIP', 'IGNORE',
    '{"type":"object","required":["migrationKey","sourceSnapshotId","dryRun"],"properties":{"migrationKey":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"sourceSnapshotId":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"dryRun":{"type":"boolean"},"afterId":{"type":"string","maxLength":255}},"additionalProperties":false}',
    '{"type":"object","required":["migrationKey","dryRun","sourceSnapshotId","exported","accepted","skipped","rejected","digestSha256","lastAfterId"]}',
    1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000492', '00000000-0000-4000-8000-000000000392',
    'migrate_legacy_assets', 'Page a read-only legacy adapter and import verified mappings', 'NORMAL',
    'asset_migrate_legacy_mappings', '1.0.0', 'scripts/main.py', '[]', TRUE, 1800,
    'FAIL_TASK', 10, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
