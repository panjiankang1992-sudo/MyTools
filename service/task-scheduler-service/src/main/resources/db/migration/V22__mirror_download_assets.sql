INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000420', '00000000-0000-4000-8000-000000000304',
    'register_asset', 'Mirror verified HTTP download output into the unified Asset Registry', 'NORMAL',
    'asset_register_content', '1.0.0', 'scripts/main.py', '[]', TRUE, 60, 'IGNORE', 20, 3,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
