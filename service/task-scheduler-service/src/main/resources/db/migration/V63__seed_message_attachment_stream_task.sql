INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000407','download_message_attachment',
    'Stream one authenticated provider attachment by opaque Messaging job id','IMMEDIATE',1800,
    '00000000-0000-4000-8000-000000000003',NULL,NULL,'SINGLE_NODE',TRUE,4,'SKIP','IGNORE',
    '{"type":"object","required":["downloadRequestId","attachmentJobId","itemId","fileName","maxBytes","ownerId"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"attachmentJobId":{"type":"string","format":"uuid"},"itemId":{"type":"string","minLength":1,"maxLength":255},"fileName":{"type":"string","minLength":1,"maxLength":255},"maxBytes":{"type":"integer","minimum":1,"maximum":21474836480},"ownerId":{"type":"integer","minimum":1}},"additionalProperties":false}',
    '{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES
('00000000-0000-4000-8000-000000000526','00000000-0000-4000-8000-000000000407',
 'download_asset','Stream and atomically publish one provider attachment','NORMAL',
 'download_message_attachment','1.0.0','scripts/main.py','[]',TRUE,1800,'FAIL_TASK',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000527','00000000-0000-4000-8000-000000000407',
 'register_asset','Register verified attachment with Asset Registry','NORMAL',
 'asset_register_content','1.0.0','scripts/main.py','[]',TRUE,60,'FAIL_TASK',20,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000528','00000000-0000-4000-8000-000000000407',
 'record_result','Record verified attachment in Download Ingestion','NORMAL',
 'download_record_result','1.0.0','scripts/main.py','[]',TRUE,30,'FAIL_TASK',30,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
