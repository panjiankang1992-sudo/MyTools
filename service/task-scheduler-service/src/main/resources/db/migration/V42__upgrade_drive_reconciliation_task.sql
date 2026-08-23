UPDATE task_definition
SET description = 'Bind Drive and successful Storage Provider root-scan digests into cutover evidence',
    parameter_schema = '{"type":"object","required":["migrationKey","accountId","storageProviderId","storageOperationId"],"properties":{"migrationKey":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"accountId":{"type":"string","format":"uuid"},"storageProviderId":{"type":"string","format":"uuid"},"storageOperationId":{"type":"string","format":"uuid"}},"additionalProperties":false}',
    result_schema = '{"type":"object","required":["migrationKey","accountId","storageProviderId","storageOperationId","matched","mismatchReasons","driveItemCount","storageItemCount","driveContentSha256","storageContentSha256"]}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000320';

UPDATE task_step_definition
SET description = 'Verify Provider root-scan ownership and compare deterministic collection digests',
    script_version = '1.1.0',
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000436';
