INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,version,created_at,updated_at)
VALUES
('00000000-0000-4000-8000-000000000325','storage_copy_tree','Copy one server-defined Provider tree without deleting the source','IMMEDIATE',43200,'00000000-0000-4000-8000-000000000008',NULL,NULL,'SINGLE_NODE',TRUE,1,'SKIP','IGNORE','{}','{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000326','storage_sync_remote','Synchronize one server-defined source tree to a target tree','IMMEDIATE',43200,'00000000-0000-4000-8000-000000000008',NULL,NULL,'SINGLE_NODE',TRUE,1,'SKIP','IGNORE','{}','{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES
('00000000-0000-4000-8000-000000000447','00000000-0000-4000-8000-000000000325','copy_tree','Start and poll an opaque rclone copy job','NORMAL','storage_transfer_tree','1.0.0','scripts/main.py','[]',TRUE,43200,'FAIL_TASK',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000448','00000000-0000-4000-8000-000000000325','on_failure','Stop remote job and mark operation failed','FAILURE','storage_finish_operation','1.1.0','scripts/main.py','[]',TRUE,90,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000449','00000000-0000-4000-8000-000000000325','on_timeout','Stop remote job and mark operation timed out','TIMEOUT','storage_finish_operation','1.1.0','scripts/main.py','[]',TRUE,90,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000450','00000000-0000-4000-8000-000000000325','on_cancel','Stop remote job and mark operation cancelled','CANCEL','storage_finish_operation','1.1.0','scripts/main.py','[]',TRUE,90,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000451','00000000-0000-4000-8000-000000000326','sync_remote','Start and poll an opaque rclone sync job','NORMAL','storage_transfer_tree','1.0.0','scripts/main.py','[]',TRUE,43200,'FAIL_TASK',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000452','00000000-0000-4000-8000-000000000326','on_failure','Stop remote job and mark operation failed','FAILURE','storage_finish_operation','1.1.0','scripts/main.py','[]',TRUE,90,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000453','00000000-0000-4000-8000-000000000326','on_timeout','Stop remote job and mark operation timed out','TIMEOUT','storage_finish_operation','1.1.0','scripts/main.py','[]',TRUE,90,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000454','00000000-0000-4000-8000-000000000326','on_cancel','Stop remote job and mark operation cancelled','CANCEL','storage_finish_operation','1.1.0','scripts/main.py','[]',TRUE,90,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

UPDATE task_step_definition SET script_version = '1.1.0', timeout_seconds = 90
WHERE script_package = 'storage_finish_operation';
