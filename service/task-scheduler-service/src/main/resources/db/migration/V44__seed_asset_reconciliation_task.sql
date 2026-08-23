INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000391', 'asset_reconcile_registry',
    'Build a deterministic bounded Asset Registry relationship report',
    'IMMEDIATE', 1800, '00000000-0000-4000-8000-000000000005', NULL, NULL, 'SINGLE_NODE', TRUE,
    1, 'SKIP', 'IGNORE',
    '{"type":"object","properties":{"afterId":{"type":"string","format":"uuid"}},"additionalProperties":false}',
    '{"type":"object","required":["registryRevision","assetCount","sourceCount","availableLocationCount","invalidLocationCount","artifactCount","bundleReferenceCount","legacyMappingCount","pageCount","digestSha256","lastAfterId"]}',
    1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000491', '00000000-0000-4000-8000-000000000391',
    'reconcile_registry', 'Page bounded asset relationship evidence', 'NORMAL',
    'asset_reconcile_registry', '1.0.0', 'scripts/main.py', '[]', TRUE, 1800,
    'FAIL_TASK', 10, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
