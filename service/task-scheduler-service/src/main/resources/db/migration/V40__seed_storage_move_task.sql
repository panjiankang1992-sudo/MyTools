INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,version,created_at,updated_at)
VALUES ('00000000-0000-4000-8000-000000000328','storage_move_tree','Copy, download-verify and remove one server-defined Provider tree','IMMEDIATE',43200,'00000000-0000-4000-8000-000000000008',NULL,NULL,'SINGLE_NODE',TRUE,1,'SKIP','IGNORE','{}','{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,version,created_at,updated_at)
VALUES ('00000000-0000-4000-8000-000000000329','storage_recover_move','Converge a remote move cleanup after bounded compensation expired','IMMEDIATE',43200,'00000000-0000-4000-8000-000000000008',NULL,NULL,'SINGLE_NODE',TRUE,1,'SKIP','IGNORE','{}','{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES
('00000000-0000-4000-8000-000000000459','00000000-0000-4000-8000-000000000328','move_tree','Advance copy, verification and source deletion','NORMAL','storage_move_tree','1.0.0','scripts/main.py','[]',TRUE,43200,'FAIL_TASK',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000460','00000000-0000-4000-8000-000000000328','on_failure','Compensate or forward-recover a failed move','FAILURE','storage_abort_move','1.0.0','scripts/main.py','[]',TRUE,90,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000461','00000000-0000-4000-8000-000000000328','on_timeout','Compensate or forward-recover a timed out move','TIMEOUT','storage_abort_move','1.0.0','scripts/main.py','[]',TRUE,90,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000462','00000000-0000-4000-8000-000000000328','on_cancel','Compensate or forward-recover a cancelled move','CANCEL','storage_abort_move','1.0.0','scripts/main.py','[]',TRUE,90,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES ('00000000-0000-4000-8000-000000000463','00000000-0000-4000-8000-000000000329','recover_move','Retry the persisted source or target cleanup action','NORMAL','storage_recover_move','1.0.0','scripts/main.py','[]',TRUE,43200,'FAIL_TASK',10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
