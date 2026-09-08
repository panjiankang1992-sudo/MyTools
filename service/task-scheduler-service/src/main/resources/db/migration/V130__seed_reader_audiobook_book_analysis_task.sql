INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, child_aggregation_policy_json, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000577', 'reader_analyze_audiobook_book',
    'Analyze frozen audiobook chapters into characters, aliases, relationships and speaker intervals',
    'IMMEDIATE', 21600, '00000000-0000-4000-8000-000000000002', NULL, NULL, 'SINGLE_NODE', TRUE,
    1, 'SKIP', 'IGNORE',
    '{"type":"object","required":["generationId","ownerId","storageRoot"],"properties":{"generationId":{"type":"string","format":"uuid"},"ownerId":{"type":"integer","minimum":1},"storageRoot":{"type":"string","minLength":1,"maxLength":128}},"additionalProperties":false}',
    '{"type":"object","required":["generationId","characterCount","relationshipCount","speechSegmentCount"],"properties":{"generationId":{"type":"string","format":"uuid"},"characterCount":{"type":"integer","minimum":0},"relationshipCount":{"type":"integer","minimum":0},"speechSegmentCount":{"type":"integer","minimum":0}},"additionalProperties":false}',
    '{"strategy":"ALL_SUCCESS","minSuccessCount":null}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000578', '00000000-0000-4000-8000-000000000577',
    'analyze_audiobook_book', 'Call the configured analysis model and atomically persist auditable book facts',
    'NORMAL', 'reader_analyze_audiobook_book', '1.0.0', 'scripts/main.py', '[]', TRUE,
    21600, 'FAIL_TASK', 10, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
