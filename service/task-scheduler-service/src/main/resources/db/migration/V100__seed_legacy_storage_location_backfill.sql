INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '10000000-0000-4000-8000-000000000001', 'asset_backfill_legacy_storage_locations',
    'Backfill Storage Gateway locations for sealed legacy media assets', 'IMMEDIATE', 3600,
    '00000000-0000-4000-8000-000000000005', NULL, NULL, 'SINGLE_NODE', TRUE, 1,
    'SKIP', 'IGNORE',
    '{"type":"object","required":["sourceSnapshotId","legacyRootPath","storageRoot","dryRun"],"properties":{"sourceSnapshotId":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"legacyRootPath":{"type":"string","minLength":1,"maxLength":4096},"storageRoot":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9._-]{0,127}$"},"dryRun":{"type":"boolean"}},"additionalProperties":false}',
    '{"type":"object","required":["sourceSnapshotId","dryRun","scanned","eligible","registered","skipped","missing","rejected"]}',
    1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '10000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001',
    'backfill_locations', 'Resolve migrated media assets and register managed storage locations', 'NORMAL',
    'asset_backfill_legacy_storage_locations', '1.0.0', 'scripts/main.py', '[]', TRUE, 3600,
    'FAIL_TASK', 10, 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
);
