UPDATE task_definition
SET description = 'Migrate one frozen legacy Reader state collection with target reconciliation evidence',
    parameter_schema = '{"type":"object","required":["migrationKey","dryRun"],"properties":{"migrationKey":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"dryRun":{"type":"boolean"},"sourceHighWater":{"type":"object","required":["SHELF","PROGRESS","MARKER"],"properties":{"SHELF":{"$ref":"#/$defs/cursor"},"PROGRESS":{"$ref":"#/$defs/cursor"},"MARKER":{"$ref":"#/$defs/cursor"}},"additionalProperties":false}},"$defs":{"cursor":{"type":"object","required":["ownerId","key"],"properties":{"ownerId":{"type":"integer","minimum":0},"key":{"type":"string","maxLength":1000}},"additionalProperties":false}},"additionalProperties":false}',
    result_schema = '{"type":"object","required":["migrationKey","dryRun","exported","accepted","skipped","rejected","digestSha256","sourceHighWater"]}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000324';

UPDATE task_step_definition
SET description = 'Migrate frozen Reader state pages and verify committed target evidence',
    script_version = '1.1.0',
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000446';
