-- 每六小时验证一次 X 登录会话并由任务包通过统一消息服务告警。
INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    'a1120000-0000-4000-8000-000000000001','download_x_auth_health',
    'Validate the configured X session and refresh its cookie file','SCHEDULED',180,
    '00000000-0000-4000-8000-000000000010','0 0 */6 * * *','Asia/Shanghai',
    'SINGLE_NODE',TRUE,1,'SKIP','FIRE_ONCE','{"type":"object","properties":{},"additionalProperties":false}',
    '{"type":"object","required":["healthy","checkedAt","probeUrl"]}',
    1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
    'a1120000-0000-4000-8000-000000000011','a1120000-0000-4000-8000-000000000001',
    'check_x_auth','Probe one X timeline and refresh authenticated cookies','NORMAL',
    'download_x_auth_health','1.0.0','scripts/main.py','[]',TRUE,180,'FAIL_TASK',10,2,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);
