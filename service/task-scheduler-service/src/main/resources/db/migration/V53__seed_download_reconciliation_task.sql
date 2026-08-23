INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000399','download_reconcile_legacy_result',
    'Compare one legacy DownloadBot result with its shadow download result','IMMEDIATE',60,
    '00000000-0000-4000-8000-000000000005',NULL,NULL,'SINGLE_NODE',TRUE,4,'SKIP','IGNORE',
    '{"type":"object","required":["sourceSnapshotId","eventId"],"properties":{"sourceSnapshotId":{"type":"string","format":"uuid"},"eventId":{"type":"string","minLength":1,"maxLength":255}},"additionalProperties":false}',
    '{"type":"object","required":["sourceSnapshotId","eventId","downloadRequestId","matched","mismatchReasons","legacy","current"]}',
    1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000511','00000000-0000-4000-8000-000000000399',
    'reconcile_result','Compare terminal status and stable content evidence','NORMAL',
    'download_reconcile_legacy_result','1.0.0','scripts/main.py','[]',TRUE,60,'FAIL_TASK',10,2,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);
