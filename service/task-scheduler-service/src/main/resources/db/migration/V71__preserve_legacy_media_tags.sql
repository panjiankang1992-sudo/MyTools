UPDATE task_definition
SET result_schema = '{"type":"object","required":["sourceSnapshotId","dryRun","exported","mediaItems","legacyTags","skippedNonMedia","imported","digestSha256"]}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE name = 'media_migrate_legacy_items';
