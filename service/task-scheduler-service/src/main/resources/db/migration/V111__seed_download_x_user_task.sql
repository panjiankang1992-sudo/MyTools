-- X 用户主页先分页读取媒体帖子，再分批创建现有单帖下载任务。
INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    'a1110000-0000-4000-8000-000000000001','download_x_user',
    'Enumerate one X user media timeline and batch existing post download tasks','IMMEDIATE',86400,
    '00000000-0000-4000-8000-000000000010',NULL,NULL,'SINGLE_NODE',TRUE,1,'SKIP','IGNORE',
    '{"type":"object","required":["downloadRequestId","url","ownerId","resourceUsername","receivedAt"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"url":{"type":"string","pattern":"^https?://","maxLength":4096},"ownerId":{"type":"integer","minimum":0},"resourceUsername":{"type":"string","pattern":"^[A-Za-z0-9_]{1,64}$"},"receivedAt":{"type":"string","format":"date-time"},"messageBatchId":{"type":"string","maxLength":64},"maxPosts":{"type":"integer","minimum":1,"maximum":10000}},"additionalProperties":false}',
    '{"type":"object","required":["requestId","username","postCount","batchCount"]}',
    1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
    'a1110000-0000-4000-8000-000000000011','a1110000-0000-4000-8000-000000000001',
    'resolve_x_user','Read X user media posts and orchestrate post download batches','NORMAL',
    'download_resolve_x_user','1.0.0','scripts/main.py','[]',TRUE,86400,'FAIL_TASK',10,2,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

-- 用户主页子任务把用户名目录传给既有单帖任务。
UPDATE task_definition
SET parameter_schema = '{"type":"object","required":["downloadRequestId","url"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"url":{"type":"string","pattern":"^https?://"},"maxMedia":{"type":"integer","minimum":1,"maximum":40},"maxBytesPerItem":{"type":"integer","minimum":1,"maximum":21474836480},"albumMediaThreshold":{"type":"integer","minimum":1,"maximum":100},"ownerId":{"type":"integer","minimum":0},"messageBatchId":{"type":"string","maxLength":64},"receivedAt":{"type":"string","format":"date-time"},"resourceUsername":{"type":"string","pattern":"^[A-Za-z0-9_]{1,64}$"},"albumFolder":{"type":"string","pattern":"^[A-Za-z0-9_]{1,64}$"}},"additionalProperties":false}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_x_post';
