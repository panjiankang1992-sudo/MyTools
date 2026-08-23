INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000305', 'media_analyze_video',
    'Probe video metadata, generate storyboard frames, and create a bounded description', 'IMMEDIATE', 480,
    '00000000-0000-4000-8000-000000000001', NULL, NULL, 'SINGLE_NODE', TRUE,
    2, 'SKIP', 'IGNORE', '{}', '{}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES
(
    '00000000-0000-4000-8000-000000000405', '00000000-0000-4000-8000-000000000305',
    'probe', 'Probe video metadata before visual analysis', 'NORMAL', 'media_probe', '1.0.0',
    'scripts/main.py', '[]', TRUE, 30, 'FAIL_TASK', 10, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
),
(
    '00000000-0000-4000-8000-000000000406', '00000000-0000-4000-8000-000000000305',
    'generate_storyboard', 'Generate evenly sampled storyboard frames', 'NORMAL',
    'media_generate_storyboard', '1.0.0', 'scripts/main.py', '[]', TRUE, 180, 'FAIL_TASK', 20, 2,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
),
(
    '00000000-0000-4000-8000-000000000407', '00000000-0000-4000-8000-000000000305',
    'describe_video', 'Generate a model or metadata video description', 'NORMAL',
    'media_describe_video', '1.0.0', 'scripts/main.py', '[]', TRUE, 240, 'FAIL_TASK', 30, 2,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
