INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, child_aggregation_policy_json, version, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000574', 'reader_import_managed_ebook',
    'Copy one owner-verified TXT media item into Reader managed storage and build its catalog',
    'IMMEDIATE', 3600, '00000000-0000-4000-8000-000000000002', NULL, NULL, 'SINGLE_NODE', TRUE,
    2, 'SKIP', 'IGNORE',
    '{"type":"object","required":["requestId","ownerId","sourceId","mediaItemId","mediaAssetId","title","mimeType","sizeBytes","contentSha256","storageRoot"],"properties":{"requestId":{"type":"string","format":"uuid"},"ownerId":{"type":"integer","minimum":1},"sourceId":{"type":"string","format":"uuid"},"mediaItemId":{"type":"string","format":"uuid"},"mediaAssetId":{"type":"string","format":"uuid"},"title":{"type":"string","minLength":1,"maxLength":300},"mimeType":{"const":"text/plain"},"sizeBytes":{"type":"integer","minimum":1,"maximum":536870912},"contentSha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"storageRoot":{"type":"string","minLength":1,"maxLength":128}},"additionalProperties":false}',
    '{"type":"object","required":["requestId","sourceId","title","chapterCount","size","sha256","storageUri"],"properties":{"requestId":{"type":"string","format":"uuid"},"sourceId":{"type":"string","format":"uuid"},"title":{"type":"string"},"chapterCount":{"type":"integer","minimum":1},"size":{"type":"integer","minimum":1},"sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"storageUri":{"type":"string","pattern":"^storage://"}},"additionalProperties":false}',
    '{"strategy":"ALL_SUCCESS","minSuccessCount":null}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000575', '00000000-0000-4000-8000-000000000574',
    'import_ebook', 'Copy and verify one frozen Media Library TXT item', 'NORMAL',
    'reader_import_managed_ebook', '1.0.0', 'scripts/main.py', '[]', TRUE, 3600, 'FAIL_TASK', 10, 2,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000576', '00000000-0000-4000-8000-000000000574',
    'extract_metadata', 'Extract deterministic metadata from the imported managed ebook', 'NORMAL',
    'reader_extract_metadata', '1.0.0', 'scripts/main.py', '[]', TRUE, 600, 'FAIL_TASK', 20, 2,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000577', '00000000-0000-4000-8000-000000000574',
    'build_catalog', 'Build a deterministic chapter catalog for the imported managed ebook', 'NORMAL',
    'reader_build_catalog', '1.0.0', 'scripts/main.py', '[]', TRUE, 600, 'FAIL_TASK', 30, 2,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
