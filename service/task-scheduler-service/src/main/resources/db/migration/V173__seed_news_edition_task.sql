-- 新闻任务和集群保持关闭，发布不可变包并验收节点后才能启用。
INSERT INTO execution_cluster (id,name,description,dispatch_strategy,max_concurrent_tasks,labels_json,enabled,created_at,updated_at)
VALUES ('a8809963-5fb3-43a7-9cd6-02f6dc1252d2','news','News edition workers','LEAST_RUNNING',2,'{}',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,child_aggregation_policy_json,version,created_at,updated_at)
VALUES ('e76a9db5-d3f5-414b-b565-9233766a92e8','news_generate_edition','Generate one immutable news edition','IMMEDIATE',120,'a8809963-5fb3-43a7-9cd6-02f6dc1252d2',NULL,NULL,'SINGLE_NODE',FALSE,2,'QUEUE','IGNORE',
'{"type":"object","required":["runId"],"properties":{"runId":{"type":"string","format":"uuid"}},"additionalProperties":false}',
'{"type":"object","required":["runId","editionId","status"],"properties":{"runId":{"type":"string","format":"uuid"},"editionId":{"type":"string","format":"uuid"},"status":{"const":"READY"}},"additionalProperties":false}',
'{"strategy":"ALL_SUCCESS","minSuccessCount":null}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES ('dcf3aeed-88bb-44d2-b819-b5dab2f80836','e76a9db5-d3f5-414b-b565-9233766a92e8','generate','Publish with live execution lease','NORMAL','news_generate_edition','1.0.0','scripts/main.py','[]',TRUE,100,'FAIL_TASK',1,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
