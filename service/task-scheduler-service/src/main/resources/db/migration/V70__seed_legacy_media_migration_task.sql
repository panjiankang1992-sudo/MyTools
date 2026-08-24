INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000530', 'media_migrate_legacy_items',
    'Preflight and import migrated legacy media assets into Media Library',
    'IMMEDIATE', 3600, '00000000-0000-4000-8000-000000000005', NULL, NULL, 'SINGLE_NODE', TRUE,
    1, 'SKIP', 'IGNORE',
    '{"type":"object","required":["sourceSnapshotId","dryRun"],"properties":{"sourceSnapshotId":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"dryRun":{"type":"boolean"}},"additionalProperties":false}',
    '{"type":"object","required":["sourceSnapshotId","dryRun","exported","mediaItems","skippedNonMedia","imported","digestSha256"]}',
    1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000531', '00000000-0000-4000-8000-000000000530',
    'migrate_legacy_media', 'Join sealed legacy assets to immutable mappings and import media items', 'NORMAL',
    'media_migrate_legacy_items', '1.0.0', 'scripts/main.py', '[]', TRUE, 3600,
    'FAIL_TASK', 10, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
