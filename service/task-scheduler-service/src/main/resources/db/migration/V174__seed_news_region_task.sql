-- 地区探测包须不可变发布并配置供应商后再显式启用。
INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,child_aggregation_policy_json,version,created_at,updated_at)
VALUES ('c1ff6ad9-9bcf-41a7-92a2-3ebc4fa9fbc7','news_resolve_region','Observe actual news execution node egress','IMMEDIATE',300,'a8809963-5fb3-43a7-9cd6-02f6dc1252d2',NULL,NULL,'SINGLE_NODE',FALSE,1,'QUEUE','IGNORE',
'{"type":"object","required":["jobId"],"properties":{"jobId":{"type":"string","format":"uuid"}},"additionalProperties":false}',
'{"type":"object","required":["jobId","observationId","status","observationStatus"],"properties":{"jobId":{"type":"string","format":"uuid"},"observationId":{"type":"string","format":"uuid"},"status":{"const":"STORED"},"observationStatus":{"enum":["RESOLVED","LOOKUP_FAILED","EGRESS_UNAVAILABLE"]}},"additionalProperties":false}',
'{"strategy":"ALL_SUCCESS","minSuccessCount":null}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES ('e6e547db-237e-4ad4-aeda-3c84cde642e0','c1ff6ad9-9bcf-41a7-92a2-3ebc4fa9fbc7','resolve','Submit region with live execution lease','NORMAL','news_resolve_region','1.1.0','scripts/main.py','[]',TRUE,90,'FAIL_TASK',1,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
