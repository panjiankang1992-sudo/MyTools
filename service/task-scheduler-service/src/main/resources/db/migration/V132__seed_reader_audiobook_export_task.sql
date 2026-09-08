INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, child_aggregation_policy_json, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000581', 'reader_export_audiobook',
    'Package a completed immutable audiobook generation into a durable ZIP archive',
    'IMMEDIATE', 21600, '00000000-0000-4000-8000-000000000002', NULL, NULL, 'SINGLE_NODE', TRUE,
    2, 'SKIP', 'IGNORE',
    '{"type":"object","required":["exportId","ownerId","storageRoot"],"properties":{"exportId":{"type":"string","format":"uuid"},"ownerId":{"type":"integer","minimum":1},"storageRoot":{"type":"string","minLength":1,"maxLength":128}},"additionalProperties":false}',
    '{"type":"object","required":["exportId","chapterCount","archiveContentSha256","archiveSizeBytes"],"properties":{"exportId":{"type":"string","format":"uuid"},"chapterCount":{"type":"integer","minimum":1},"archiveContentSha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"archiveSizeBytes":{"type":"integer","minimum":1}},"additionalProperties":false}',
    '{"strategy":"ALL_SUCCESS","minSuccessCount":null}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000582', '00000000-0000-4000-8000-000000000581',
    'export_audiobook_zip', 'Verify immutable chapter MP3 assets and publish one durable ZIP archive',
    'NORMAL', 'reader_export_audiobook', '1.0.0', 'scripts/main.py', '[]', TRUE,
    21600, 'FAIL_TASK', 10, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
