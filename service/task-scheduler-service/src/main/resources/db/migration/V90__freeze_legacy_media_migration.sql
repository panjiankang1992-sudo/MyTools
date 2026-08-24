UPDATE task_definition
SET parameter_schema = '{"type":"object","required":["migrationKey","sourceSnapshotId","dryRun"],"properties":{"migrationKey":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"sourceSnapshotId":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"dryRun":{"type":"boolean"}},"additionalProperties":false}',
    result_schema = '{"type":"object","required":["migrationKey","sourceSnapshotId","dryRun","exported","mediaItems","legacyTags","skippedNonMedia","imported","digestSha256","targetVerified"]}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE name = 'media_migrate_legacy_items';

UPDATE task_step_definition
SET script_version = '1.1.0',
    description = 'Join one frozen snapshot to immutable assets and verify exact target evidence',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE task_definition_id = '00000000-0000-4000-8000-000000000530'
  AND name = 'migrate_legacy_media';
