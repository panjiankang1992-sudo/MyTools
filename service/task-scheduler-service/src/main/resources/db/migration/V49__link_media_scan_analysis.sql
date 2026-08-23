UPDATE task_definition SET
    parameter_schema='{"type":"object","required":["assetId","contentSha256","sourcePath","ownerId","assetSourceType","assetSourceBusinessId","assetMimeType","assetProviderType","assetProviderVersion","directoryKey","directoryName","scanId"],"properties":{"assetId":{"type":"string","maxLength":255},"contentSha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"sourcePath":{"type":"string","minLength":1,"maxLength":4096},"ownerId":{"type":"integer","minimum":0},"assetSourceType":{"const":"MEDIA_SCAN"},"assetSourceBusinessId":{"type":"string","minLength":1,"maxLength":255},"assetMimeType":{"type":"string","minLength":1,"maxLength":255},"assetProviderType":{"const":"LEGACY_MEDIA"},"assetProviderVersion":{"type":"string","minLength":1,"maxLength":255},"directoryKey":{"type":"string","minLength":1,"maxLength":255},"directoryName":{"type":"string","minLength":1,"maxLength":512},"scanId":{"type":"string","format":"uuid"},"analyze":{"type":"boolean"},"analysisVersion":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,64}$"},"storageRoot":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9._-]{0,127}$"},"frameCount":{"type":"integer","minimum":1,"maximum":12},"seekSeconds":{"type":"number","minimum":0}},"additionalProperties":false}',
    version=2,updated_at=CURRENT_TIMESTAMP
WHERE id='00000000-0000-4000-8000-000000000394';

UPDATE task_definition SET
    parameter_schema='{"type":"object","required":["rootPath","directoryKey","directoryName","ownerId"],"properties":{"rootPath":{"type":"string","minLength":1,"maxLength":4096},"directoryKey":{"type":"string","minLength":1,"maxLength":255},"directoryName":{"type":"string","minLength":1,"maxLength":512},"ownerId":{"type":"integer","minimum":0},"scanKey":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,255}$"},"childTimeoutSeconds":{"type":"integer","minimum":30,"maximum":3600},"analyze":{"type":"boolean"},"analysisVersion":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,64}$"},"storageRoot":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9._-]{0,127}$"},"frameCount":{"type":"integer","minimum":1,"maximum":12},"seekSeconds":{"type":"number","minimum":0}},"additionalProperties":false}',
    version=2,updated_at=CURRENT_TIMESTAMP
WHERE id='00000000-0000-4000-8000-000000000395';

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000507','00000000-0000-4000-8000-000000000394',
    'submit_analysis','Optionally create a same-node versioned media analysis child','NORMAL',
    'media_submit_analysis','1.0.0','scripts/main.py','[]',TRUE,30,'FAIL_TASK',40,3,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);
