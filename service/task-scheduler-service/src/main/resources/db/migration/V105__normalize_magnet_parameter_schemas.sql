UPDATE task_definition
SET parameter_schema = '{"type":"object","required":["downloadRequestId","magnetUri"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"magnetUri":{"type":"string","pattern":"^magnet:","maxLength":8192},"maxTotalBytes":{"type":"integer","minimum":1,"maximum":107374182400},"destinationRootName":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},"ownerId":{"type":"integer","minimum":0}},"additionalProperties":false}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_local_magnet';

UPDATE task_definition
SET parameter_schema = '{"type":"object","required":["downloadRequestId","accountId","magnetUri"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"accountId":{"type":"string","format":"uuid"},"magnetUri":{"type":"string","pattern":"^magnet:","maxLength":8192},"maximumAdvances":{"type":"integer","minimum":1,"maximum":4320},"maxBytesPerItem":{"type":"integer","minimum":1,"maximum":107374182400},"destinationRootName":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},"ownerId":{"type":"integer","minimum":0}},"additionalProperties":false}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_pikpak_magnet';
