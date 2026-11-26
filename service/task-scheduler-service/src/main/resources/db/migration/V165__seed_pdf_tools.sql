-- 独立 CPU 集群通过验收后显式启用，避免未安装字体或沙箱的节点领取任务。
INSERT INTO execution_cluster (id,name,description,dispatch_strategy,max_concurrent_tasks,labels_json,enabled,created_at,updated_at)
VALUES ('cfa41a95-919d-4f2b-bcaa-25d416c8d1b8','pdf-processing','Offline PDF processing workers','LEAST_RUNNING',2,'{"pdf.processing":"enabled"}',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,child_aggregation_policy_json,version,created_at,updated_at)
VALUES ('6b4b3584-e955-4289-9558-6b3a3152422d','pdf_process','Inspect or render a frozen PDF plan','IMMEDIATE',300,'cfa41a95-919d-4f2b-bcaa-25d416c8d1b8',NULL,NULL,'SINGLE_NODE',FALSE,2,'QUEUE','IGNORE',
'{"type":"object","required":["workId"],"properties":{"workId":{"type":"string","format":"uuid"}},"additionalProperties":false}',
'{"type":"object","required":["workId","completed"],"properties":{"workId":{"type":"string","format":"uuid"},"completed":{"type":"boolean"}},"additionalProperties":false}',
'{"strategy":"ALL_SUCCESS","minSuccessCount":null}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES ('58a16061-5d97-4e87-ac55-e6914f7bfb62','6b4b3584-e955-4289-9558-6b3a3152422d','process','Isolated PDF worker with fenced publication','NORMAL','pdf_process','1.0.0','scripts/main.py','[]',TRUE,300,'FAIL_TASK',1,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
