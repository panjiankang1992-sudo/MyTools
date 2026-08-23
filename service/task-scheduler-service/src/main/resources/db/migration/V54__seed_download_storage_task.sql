INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000400','download_storage_object',
    'Verify and copy one managed Storage Gateway object','IMMEDIATE',1800,
    '00000000-0000-4000-8000-000000000003',NULL,NULL,'SINGLE_NODE',TRUE,4,'SKIP','IGNORE',
    '{"type":"object","required":["downloadRequestId","itemId","sourceStorageUri","fileName"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"itemId":{"type":"string","minLength":1,"maxLength":255},"sourceStorageUri":{"type":"string","pattern":"^storage://"},"fileName":{"type":"string","minLength":1,"maxLength":255},"expectedSha256":{"type":"string","pattern":"^[a-fA-F0-9]{64}$"},"maxBytes":{"type":"integer","minimum":1,"maximum":107374182400},"destinationRootName":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},"ownerId":{"type":"integer","minimum":0}},"additionalProperties":false}',
    '{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES
('00000000-0000-4000-8000-000000000512','00000000-0000-4000-8000-000000000400',
 'download_asset','Copy one verified managed object','NORMAL','download_storage_object','1.0.0',
 'scripts/main.py','[]',TRUE,1800,'FAIL_TASK',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000513','00000000-0000-4000-8000-000000000400',
 'register_asset','Register copied content in Asset Registry','NORMAL','asset_register_content','1.0.0',
 'scripts/main.py','[]',TRUE,60,'FAIL_TASK',20,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000514','00000000-0000-4000-8000-000000000400',
 'record_result','Record copied content in Download Ingestion','NORMAL','download_record_result','1.0.0',
 'scripts/main.py','[]',TRUE,30,'FAIL_TASK',30,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
