INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000394','media_ingest_scanned_file',
    'Probe and register one file discovered by a Media Library scan','IMMEDIATE',300,
    '00000000-0000-4000-8000-000000000001',NULL,NULL,'SINGLE_NODE',TRUE,8,'SKIP','IGNORE',
    '{"type":"object","required":["assetId","contentSha256","sourcePath","ownerId","assetSourceType","assetSourceBusinessId","assetMimeType","assetProviderType","assetProviderVersion","directoryKey","directoryName","scanId"],"properties":{"assetId":{"type":"string","maxLength":255},"contentSha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"sourcePath":{"type":"string","minLength":1,"maxLength":4096},"ownerId":{"type":"integer","minimum":0},"assetSourceType":{"const":"MEDIA_SCAN"},"assetSourceBusinessId":{"type":"string","minLength":1,"maxLength":255},"assetMimeType":{"type":"string","minLength":1,"maxLength":255},"assetProviderType":{"const":"LEGACY_MEDIA"},"assetProviderVersion":{"type":"string","minLength":1,"maxLength":255},"directoryKey":{"type":"string","minLength":1,"maxLength":255},"directoryName":{"type":"string","minLength":1,"maxLength":512},"scanId":{"type":"string","format":"uuid"}},"additionalProperties":false}',
    '{"type":"object"}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES
('00000000-0000-4000-8000-000000000494','00000000-0000-4000-8000-000000000394','probe','Probe stable media metadata','NORMAL','media_probe','1.0.0','scripts/main.py','[]',TRUE,60,'FAIL_TASK',10,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000495','00000000-0000-4000-8000-000000000394','register_asset','Register scanned content in Asset Registry','NORMAL','asset_register_content','1.0.0','scripts/main.py','[]',TRUE,60,'FAIL_TASK',20,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000496','00000000-0000-4000-8000-000000000394','register_media_item','Commit the registered asset to the staged Media Library scan','NORMAL','media_register_item','1.0.0','scripts/main.py','[]',TRUE,60,'FAIL_TASK',30,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000395','media_scan_directory',
    'Scan one allow-listed directory and publish a complete Media Library generation','IMMEDIATE',7200,
    '00000000-0000-4000-8000-000000000001',NULL,NULL,'SINGLE_NODE',TRUE,1,'SKIP','IGNORE',
    '{"type":"object","required":["rootPath","directoryKey","directoryName","ownerId"],"properties":{"rootPath":{"type":"string","minLength":1,"maxLength":4096},"directoryKey":{"type":"string","minLength":1,"maxLength":255},"directoryName":{"type":"string","minLength":1,"maxLength":512},"ownerId":{"type":"integer","minimum":0},"scanKey":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,255}$"},"childTimeoutSeconds":{"type":"integer","minimum":30,"maximum":3600}},"additionalProperties":false}',
    '{"type":"object","required":["scanId","manifestSha256","discovered","imported","childTaskIds"]}',
    1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000497','00000000-0000-4000-8000-000000000395',
    'scan_directory','Build, ingest, and atomically publish one media directory generation','NORMAL',
    'media_scan_directory','1.0.0','scripts/main.py','[]',TRUE,7200,'FAIL_TASK',10,2,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);
