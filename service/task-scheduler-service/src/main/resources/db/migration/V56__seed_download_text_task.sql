INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000402','download_publish_text',
    'Publish one bounded generated text as a managed download result','IMMEDIATE',120,
    '00000000-0000-4000-8000-000000000003',NULL,NULL,'SINGLE_NODE',TRUE,4,'SKIP','IGNORE',
    '{"type":"object","required":["downloadRequestId","itemId","fileName","content"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"itemId":{"type":"string","minLength":1,"maxLength":255},"fileName":{"type":"string","minLength":1,"maxLength":255},"content":{"type":"string","minLength":1,"maxLength":2097152},"destinationRootName":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},"ownerId":{"type":"integer","minimum":0},"assetMimeType":{"const":"text/plain"},"assetSourceBusinessId":{"type":"string","minLength":1,"maxLength":255}},"additionalProperties":false}',
    '{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES
('00000000-0000-4000-8000-000000000516','00000000-0000-4000-8000-000000000402',
 'download_asset','Publish bounded UTF-8 text through Storage Gateway','NORMAL','download_publish_text',
 '1.0.0','scripts/main.py','[]',TRUE,120,'FAIL_TASK',10,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000517','00000000-0000-4000-8000-000000000402',
 'register_asset','Register generated text in Asset Registry','NORMAL','asset_register_content',
 '1.0.0','scripts/main.py','[]',TRUE,60,'FAIL_TASK',20,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000518','00000000-0000-4000-8000-000000000402',
 'record_result','Record generated text in Download Ingestion','NORMAL','download_record_result',
 '1.0.0','scripts/main.py','[]',TRUE,30,'FAIL_TASK',30,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
