INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES
('00000000-0000-4000-8000-000000000424','00000000-0000-4000-8000-000000000315','on_failure','Mark Drive index run failed','FAILURE','drive_finish_index','1.0.0','scripts/main.py','[]',TRUE,30,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000425','00000000-0000-4000-8000-000000000315','on_timeout','Mark Drive index run timed out','TIMEOUT','drive_finish_index','1.0.0','scripts/main.py','[]',TRUE,30,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000426','00000000-0000-4000-8000-000000000315','on_cancel','Mark Drive index run cancelled','CANCEL','drive_finish_index','1.0.0','scripts/main.py','[]',TRUE,30,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
