INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000562','00000000-0000-4000-8000-000000000394',
    'publish_asset','Reverify and publish the scanned file through Storage Gateway','NORMAL',
    'media_publish_scanned_file','1.0.0','scripts/main.py','[]',TRUE,3600,'FAIL_TASK',15,3,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

UPDATE task_definition SET
    description='Probe, durably publish, and register one file discovered by a Media Library scan',
    parameter_schema='{"type":"object","required":["assetId","contentSha256","sizeBytes","sourcePath","ownerId","assetSourceType","assetSourceBusinessId","assetMimeType","assetProviderType","assetProviderVersion","directoryKey","directoryName","scanId"],"properties":{"assetId":{"type":"string","maxLength":255},"contentSha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"sizeBytes":{"type":"integer","minimum":1},"sourcePath":{"type":"string","minLength":1,"maxLength":4096},"ownerId":{"type":"integer","minimum":0},"assetSourceType":{"const":"MEDIA_SCAN"},"assetSourceBusinessId":{"type":"string","minLength":1,"maxLength":255},"assetMimeType":{"type":"string","minLength":1,"maxLength":255},"assetProviderType":{"const":"STORAGE_GATEWAY"},"assetProviderVersion":{"type":"string","minLength":1,"maxLength":255},"directoryKey":{"type":"string","minLength":1,"maxLength":255},"directoryName":{"type":"string","minLength":1,"maxLength":512},"scanId":{"type":"string","format":"uuid"},"analyze":{"type":"boolean"},"analysisVersion":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,64}$"},"storageRoot":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9._-]{0,127}$"},"frameCount":{"type":"integer","minimum":1,"maximum":12},"seekSeconds":{"type":"number","minimum":0}},"additionalProperties":false}',
    version=3,updated_at=CURRENT_TIMESTAMP
WHERE id='00000000-0000-4000-8000-000000000394';
