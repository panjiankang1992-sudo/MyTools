UPDATE task_definition
SET parameter_schema = '{"type":"object","required":["snapshotId","ownerId"],"properties":{"snapshotId":{"type":"string","pattern":"^[A-Za-z0-9._:-]{1,128}$"},"ownerId":{"type":"integer","minimum":1}},"additionalProperties":false}',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE name = 'legacy_asset_capture_snapshot';
