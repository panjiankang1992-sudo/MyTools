UPDATE task_definition
SET description = 'Dry-run or migrate sanitized Drive Provider references with digest reconciliation',
    parameter_schema = '{"type":"object","required":["migrationKey","dryRun"],"properties":{"migrationKey":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"dryRun":{"type":"boolean"},"afterId":{"type":"string","maxLength":255}},"additionalProperties":false}',
    result_schema = '{"type":"object","required":["migrationKey","dryRun","exported","accepted","bound","rejected","digestSha256","lastAfterId"]}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000319';

UPDATE task_step_definition
SET description = 'Dry-run or register and bind sanitized Drive Provider references',
    script_version = '1.1.0',
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000435';
