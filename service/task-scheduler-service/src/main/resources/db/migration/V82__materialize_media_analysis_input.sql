UPDATE task_definition SET
    description='Materialize a registered storage asset, run versioned media intelligence, and atomically commit results',
    parameter_schema='{"type":"object","required":["mediaItemId","assetRegistryId","assetId","analysisVersion","contentSha256","ownerId","filename","mimeType"],"properties":{"mediaItemId":{"type":"string","format":"uuid"},"assetRegistryId":{"type":"string","format":"uuid"},"assetId":{"type":"string","minLength":1,"maxLength":255},"analysisVersion":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,64}$"},"sourcePath":{"type":"string","minLength":1,"maxLength":4096},"contentSha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"ownerId":{"type":"integer","minimum":0},"filename":{"type":"string","minLength":1,"maxLength":512},"mimeType":{"type":"string","minLength":1,"maxLength":255},"assetMimeType":{"type":"string","minLength":1,"maxLength":255},"storageRoot":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9._-]{0,127}$"},"frameCount":{"type":"integer","minimum":1,"maximum":12},"seekSeconds":{"type":"number","minimum":0}},"additionalProperties":false}',
    version=3,updated_at=CURRENT_TIMESTAMP
WHERE id='00000000-0000-4000-8000-000000000305';

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000557','00000000-0000-4000-8000-000000000305',
    'materialize_input','Stream and verify the registered Storage Gateway object in the executor workspace',
    'NORMAL','media_materialize_input','1.0.0','scripts/main.py','[]',TRUE,300,'FAIL_TASK',8,2,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);
