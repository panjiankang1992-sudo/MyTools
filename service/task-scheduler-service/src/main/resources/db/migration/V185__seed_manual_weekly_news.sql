-- 只有用户点击才创建实例；任务默认关闭且没有 cron，验收专用执行节点后再启用。
CREATE TABLE IF NOT EXISTS news_weekly_deployment_audit (
    id VARCHAR(100) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    service_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
-- 线上可能从未启用旧日报，不依赖旧新闻种子已执行。
INSERT INTO execution_cluster (id,name,description,dispatch_strategy,max_concurrent_tasks,labels_json,enabled,created_at,updated_at)
SELECT 'a8809963-5fb3-43a7-9cd6-02f6dc1252d2','news','Manual news workers','LEAST_RUNNING',1,'{}',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM execution_cluster WHERE name='news');
INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,child_aggregation_policy_json,version,created_at,updated_at)
VALUES ('945dd28a-fc94-4a06-aeb1-b8cd78907fbe','news_generate_weekly','Generate manual seven-day news report','IMMEDIATE',7200,(SELECT id FROM execution_cluster WHERE name='news'),NULL,NULL,'SINGLE_NODE',FALSE,1,'QUEUE','IGNORE',
'{"type":"object","required":["jobId"],"properties":{"jobId":{"type":"string","format":"uuid"}},"additionalProperties":false}',
'{"type":"object","required":["jobId","status","reportRevision"],"properties":{"jobId":{"type":"string","format":"uuid"},"status":{"enum":["SUCCEEDED","PARTIAL","FAILED"]},"reportRevision":{"type":"integer","minimum":0}},"additionalProperties":false}',
'{"strategy":"ALL_SUCCESS","minSuccessCount":null}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES ('f1fbc565-d17d-45b0-80c7-f652192a9df8','945dd28a-fc94-4a06-aeb1-b8cd78907fbe','generate','Collect analyze and publish under current execution lease','NORMAL','news_generate_weekly','1.0.0','scripts/main.py','[]',TRUE,7100,'FAIL_TASK',1,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
