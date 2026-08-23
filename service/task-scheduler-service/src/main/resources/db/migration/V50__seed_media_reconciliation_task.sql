INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000396','media_reconcile_library',
    'Produce a stable revision-bound Media Library reconciliation report','IMMEDIATE',900,
    '00000000-0000-4000-8000-000000000001',NULL,NULL,'SINGLE_NODE',TRUE,1,'SKIP','IGNORE',
    '{"type":"object","properties":{"requireQuiescent":{"type":"boolean"}},"additionalProperties":false}',
    '{"type":"object","required":["libraryRevision","directoryCount","completedScanCount","stagingScanCount","itemCount","readyCount","missingCount","analyzingCount","succeededAnalysisCount","failedAnalysisCount","runningAnalysisCount","tagRelationCount","artifactCount","readyDirectoryEntryCount","missingDirectoryEntryCount","digestSha256"]}',
    1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000508','00000000-0000-4000-8000-000000000396',
    'reconcile_library','Page and aggregate one stable Media Library revision','NORMAL',
    'media_reconcile_library','1.0.0','scripts/main.py','[]',TRUE,900,'FAIL_TASK',10,2,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);
