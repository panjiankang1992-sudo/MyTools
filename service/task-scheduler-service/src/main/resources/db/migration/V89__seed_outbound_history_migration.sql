INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000562','message_migrate_outbound_history',
    'Migrate one frozen MsgService outbound archive without redelivery','IMMEDIATE',1800,
    '00000000-0000-4000-8000-000000000005',NULL,NULL,'SINGLE_NODE',TRUE,1,'SKIP','IGNORE',
    '{"type":"object","required":["migrationKey","dryRun"],"properties":{"migrationKey":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"dryRun":{"type":"boolean"}},"additionalProperties":false}',
    '{"type":"object","required":["migrationKey","dryRun","exported","accepted","skipped","rejected","digestSha256","sourceItemCount","sourceDigestSha256","sourceHighWater"]}',
    1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,
    created_at,updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000563','00000000-0000-4000-8000-000000000562',
    'migrate_outbound_history','Archive frozen outbound pages and verify committed target evidence',
    'NORMAL','message_migrate_outbound_history','1.0.0','scripts/main.py','[]',TRUE,1800,
    'FAIL_TASK',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
);
