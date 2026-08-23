INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000403','download_web_archive',
    'Resolve one public web page into text and HTTP resource child tasks','IMMEDIATE',1800,
    '00000000-0000-4000-8000-000000000010',NULL,NULL,'SINGLE_NODE',TRUE,2,'SKIP','IGNORE',
    '{"type":"object","required":["downloadRequestId","url"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"url":{"type":"string","pattern":"^https?://"},"maxPageBytes":{"type":"integer","minimum":1,"maximum":8388608},"minTextBytes":{"type":"integer","minimum":0,"maximum":2097152},"maxTextBytes":{"type":"integer","minimum":1,"maximum":2097152},"maxAssets":{"type":"integer","minimum":0,"maximum":100},"maxBytesPerItem":{"type":"integer","minimum":1,"maximum":21474836480},"ownerId":{"type":"integer","minimum":0}},"additionalProperties":false}',
    '{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000519','00000000-0000-4000-8000-000000000403',
    'resolve_web_archive','Fetch public HTML and orchestrate text and media children','NORMAL',
    'download_resolve_web_archive','1.0.0','scripts/main.py','[]',TRUE,1800,'FAIL_TASK',10,2,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);
