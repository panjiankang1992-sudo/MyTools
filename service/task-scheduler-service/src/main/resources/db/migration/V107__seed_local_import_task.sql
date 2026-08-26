INSERT INTO task_definition (
 id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
 execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
 result_schema,version,created_at,updated_at
) VALUES (
 '00000000-0000-4000-8000-000000000415','download_local_import',
 'Expand and classify one managed local import object','IMMEDIATE',21600,
 '00000000-0000-4000-8000-000000000003',NULL,NULL,'SINGLE_NODE',TRUE,2,'SKIP','IGNORE',
 '{"type":"object","required":["downloadRequestId","sourceStorageUri","fileName"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"sourceStorageUri":{"type":"string","pattern":"^storage://"},"fileName":{"type":"string","minLength":1,"maxLength":255},"expectedSha256":{"type":"string","pattern":"^[a-fA-F0-9]{64}$"},"maxBytes":{"type":"integer","minimum":1,"maximum":107374182400},"maxArchiveDepth":{"type":"integer","minimum":1,"maximum":4},"albumMaxItems":{"type":"integer","minimum":1,"maximum":100},"destinationRootName":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},"ownerId":{"type":"integer","minimum":0}} ,"additionalProperties":false}',
 '{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
 id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
 arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
 '00000000-0000-4000-8000-000000000800','00000000-0000-4000-8000-000000000415',
 'download_local_import','Expand archives and orchestrate classified objects','NORMAL',
 'download_local_import','1.0.0','scripts/main.py','[]',TRUE,21600,'FAIL_TASK',10,3,
 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

UPDATE task_definition
SET parameter_schema = '{"type":"object","required":["downloadRequestId","itemId","sourceStorageUri","fileName"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"itemId":{"type":"string","minLength":1,"maxLength":255},"sourceStorageUri":{"type":"string","pattern":"^storage://"},"fileName":{"type":"string","minLength":1,"maxLength":255},"expectedSha256":{"type":"string","pattern":"^[a-fA-F0-9]{64}$"},"maxBytes":{"type":"integer","minimum":1,"maximum":107374182400},"destinationRootName":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},"ownerId":{"type":"integer","minimum":0},"receivedAt":{"type":"string","format":"date-time"},"assetMimeType":{"type":"string","maxLength":255},"albumFolder":{"type":"string","maxLength":128},"assetSourceBusinessId":{"type":"string","maxLength":255}} ,"additionalProperties":false}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_storage_object';
