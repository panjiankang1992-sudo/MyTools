INSERT INTO task_definition (
 id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
 execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
 result_schema,version,created_at,updated_at
) VALUES (
 '00000000-0000-4000-8000-000000000414','download_local_magnet',
 'Resume a bounded local magnet download with aria2','IMMEDIATE',21600,
 '00000000-0000-4000-8000-000000000003',NULL,NULL,'SINGLE_NODE',TRUE,2,'SKIP','IGNORE',
 '{"type":"object","required":["downloadRequestId","magnetUri"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"magnetUri":{"type":"string","pattern":"^magnet:\\?","maxLength":8192},"maxTotalBytes":{"type":"integer","minimum":1,"maximum":107374182400},"destinationRootName":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},"ownerId":{"type":"integer","minimum":0}},"additionalProperties":false}',
 '{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
 id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
 arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
 '00000000-0000-4000-8000-000000000534','00000000-0000-4000-8000-000000000414',
 'download_local_magnet','Resume magnet content and orchestrate managed imports','NORMAL',
 'download_local_magnet','1.0.0','scripts/main.py','[]',TRUE,21600,'FAIL_TASK',10,3,
 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);
