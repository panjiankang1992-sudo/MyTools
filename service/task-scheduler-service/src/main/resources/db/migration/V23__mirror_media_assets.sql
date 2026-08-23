INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES
(
    '00000000-0000-4000-8000-000000000421', '00000000-0000-4000-8000-000000000301',
    'register_asset', 'Mirror probed media into the unified Asset Registry', 'NORMAL',
    'asset_register_content', '1.0.0', 'scripts/main.py', '[]', TRUE, 60, 'IGNORE', 20, 3,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
),
(
    '00000000-0000-4000-8000-000000000422', '00000000-0000-4000-8000-000000000302',
    'register_thumbnail', 'Publish and register the generated thumbnail as a derived asset', 'NORMAL',
    'asset_register_media_thumbnail', '1.0.0', 'scripts/main.py', '[]', TRUE, 90, 'IGNORE', 20, 3,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
