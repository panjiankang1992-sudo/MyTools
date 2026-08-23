INSERT INTO execution_cluster (id,name,description,dispatch_strategy,max_concurrent_tasks,labels_json,enabled,created_at,updated_at)
VALUES ('00000000-0000-4000-8000-000000000008','storage','Storage operation workers','LEAST_RUNNING',2,'{"workload":"storage"}',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,version,created_at,updated_at)
VALUES ('00000000-0000-4000-8000-000000000318','storage_scan_root','Scan one registered storage Provider tree','IMMEDIATE',3600,'00000000-0000-4000-8000-000000000008',NULL,NULL,'SINGLE_NODE',TRUE,2,'SKIP','IGNORE','{}','{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES
('00000000-0000-4000-8000-000000000431','00000000-0000-4000-8000-000000000318','scan_root','Breadth-first scan and merge object batches','NORMAL','storage_scan_root','1.0.0','scripts/main.py','[]',TRUE,3600,'FAIL_TASK',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000432','00000000-0000-4000-8000-000000000318','on_failure','Mark storage operation failed','FAILURE','storage_finish_operation','1.0.0','scripts/main.py','[]',TRUE,30,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000433','00000000-0000-4000-8000-000000000318','on_timeout','Mark storage operation timed out','TIMEOUT','storage_finish_operation','1.0.0','scripts/main.py','[]',TRUE,30,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000434','00000000-0000-4000-8000-000000000318','on_cancel','Mark storage operation cancelled','CANCEL','storage_finish_operation','1.0.0','scripts/main.py','[]',TRUE,30,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
