-- 新版投递任务保持关闭，发布包并完成目的地和渠道验收后再启用。
INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,child_aggregation_policy_json,version,created_at,updated_at)
VALUES ('2b041a67-81e3-49a8-b592-aa55e5b590fe','message_send_channel_message','Deliver frozen content to a verified destination','IMMEDIATE',120,'00000000-0000-4000-8000-000000000004',NULL,NULL,'SINGLE_NODE',FALSE,2,'QUEUE','IGNORE',
'{"type":"object","required":["deliveryId","generation"],"properties":{"deliveryId":{"type":"string","format":"uuid"},"generation":{"type":"integer","minimum":1,"maximum":100}},"additionalProperties":false}',
'{"type":"object","required":["deliveryId","status"],"properties":{"deliveryId":{"type":"string","format":"uuid"},"status":{"enum":["PROVIDER_ACCEPTED","FAILED","UNKNOWN","CANCELLED","EXPIRED"]}},"additionalProperties":false}',
'{"strategy":"ALL_SUCCESS","minSuccessCount":null}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES ('13d1a15a-4d74-456d-b382-d759120c90b1','2b041a67-81e3-49a8-b592-aa55e5b590fe','send_channel','Send with live lease and persistent attempt','NORMAL','message_send_channel_message','1.0.0','scripts/main.py','[]',TRUE,100,'FAIL_TASK',1,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
