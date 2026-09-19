INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,child_aggregation_policy_json,version,created_at,updated_at
) SELECT
    '00000000-0000-4000-8000-000000000564','onebot_relogin',
    'Request one idempotent OneBot relogin and wait for a fresh QR code','IMMEDIATE',180,
    '00000000-0000-4000-8000-000000000004',NULL,NULL,'SINGLE_NODE',TRUE,1,'QUEUE','IGNORE',
    '{"type":"object","required":["accountKey","requestId"],"properties":{"accountKey":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"},"requestId":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"}},"additionalProperties":false}',
    '{"oneOf":[{"type":"object","required":["accountKey","requestId","requestedAt","status"],"properties":{"accountKey":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"},"requestId":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"},"requestedAt":{"type":"string","format":"date-time"},"status":{"const":"QR_READY"}},"additionalProperties":false},{"type":"object","required":["accountKey","requestId","status"],"properties":{"accountKey":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"},"requestId":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"},"status":{"const":"ALREADY_ONLINE"}},"additionalProperties":false}]}',
    '{"strategy":"ALL_SUCCESS","minSuccessCount":null}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM task_definition WHERE name = 'onebot_relogin' AND version = 1
);

UPDATE task_definition
SET description = 'Request one idempotent OneBot relogin and wait for a fresh QR code',
    task_type = 'IMMEDIATE',
    timeout_seconds = 180,
    cluster_id = '00000000-0000-4000-8000-000000000004',
    cron_expression = NULL,
    cron_timezone = NULL,
    execution_mode = 'SINGLE_NODE',
    enabled = TRUE,
    max_concurrency = 1,
    overlap_policy = 'QUEUE',
    misfire_policy = 'IGNORE',
    parameter_schema = '{"type":"object","required":["accountKey","requestId"],"properties":{"accountKey":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"},"requestId":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"}},"additionalProperties":false}',
    result_schema = '{"oneOf":[{"type":"object","required":["accountKey","requestId","requestedAt","status"],"properties":{"accountKey":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"},"requestId":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"},"requestedAt":{"type":"string","format":"date-time"},"status":{"const":"QR_READY"}},"additionalProperties":false},{"type":"object","required":["accountKey","requestId","status"],"properties":{"accountKey":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"},"requestId":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,128}$"},"status":{"const":"ALREADY_ONLINE"}},"additionalProperties":false}]}',
    child_aggregation_policy_json = '{"strategy":"ALL_SUCCESS","minSuccessCount":null}',
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'onebot_relogin' AND version = 1;

UPDATE task_step_definition
SET name = 'request_relogin'
WHERE task_definition_id = (
        SELECT id FROM task_definition WHERE name = 'onebot_relogin' AND version = 1
    )
  AND name = 'request_login_qr'
  AND task_definition_id NOT IN (
      SELECT existing_definition_id
      FROM (
          SELECT task_definition_id AS existing_definition_id
          FROM task_step_definition
          WHERE name = 'request_relogin'
          GROUP BY task_definition_id
      ) existing_relogin_steps
  );

UPDATE task_step_definition
SET description = 'Invoke OneBot relogin and wait for its fresh QR readiness',
    step_kind = 'NORMAL',
    script_package = 'onebot_relogin',
    script_version = '1.0.0',
    entrypoint = 'scripts/main.py',
    arguments_template = '[]',
    enabled = TRUE,
    timeout_seconds = 180,
    failure_policy = 'FAIL_TASK',
    sequence_number = 10,
    max_attempts = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = (
        SELECT id FROM task_definition WHERE name = 'onebot_relogin' AND version = 1
    )
  AND name = 'request_relogin';

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,
    created_at,updated_at
) SELECT
    '00000000-0000-4000-8000-000000000565',definition.id,
    'request_relogin','Invoke OneBot relogin and wait for its fresh QR readiness','NORMAL',
    'onebot_relogin','1.0.0','scripts/main.py','[]',TRUE,180,'FAIL_TASK',10,1,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM task_definition definition
WHERE definition.name = 'onebot_relogin'
  AND definition.version = 1
  AND NOT EXISTS (
      SELECT 1
      FROM task_step_definition step_definition
      WHERE step_definition.task_definition_id = definition.id
        AND step_definition.name = 'request_relogin'
  );
