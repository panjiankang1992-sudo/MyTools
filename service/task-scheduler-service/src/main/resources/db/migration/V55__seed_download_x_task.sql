INSERT INTO execution_cluster (
    id,name,description,dispatch_strategy,max_concurrent_tasks,labels_json,enabled,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000010','download-orchestration',
    'Download resolver and child-task orchestration workers','LEAST_RUNNING',4,
    '{"workload":"download-orchestration"}',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000401','download_x_post',
    'Resolve one X post and wait for bounded HTTP media child tasks','IMMEDIATE',1800,
    '00000000-0000-4000-8000-000000000010',NULL,NULL,'SINGLE_NODE',TRUE,2,'SKIP','IGNORE',
    '{"type":"object","required":["downloadRequestId","url"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"url":{"type":"string","pattern":"^https?://"},"maxMedia":{"type":"integer","minimum":1,"maximum":40},"maxBytesPerItem":{"type":"integer","minimum":1,"maximum":21474836480},"ownerId":{"type":"integer","minimum":0}},"additionalProperties":false}',
    '{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000515','00000000-0000-4000-8000-000000000401',
    'resolve_x_post','Resolve media and orchestrate atomic HTTP children','NORMAL',
    'download_resolve_x_post','1.0.0','scripts/main.py','[]',TRUE,1800,'FAIL_TASK',10,2,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);
