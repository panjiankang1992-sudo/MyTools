UPDATE task_definition
SET parameter_schema = '{"type":"object","required":["downloadRequestId","ownerId","messageBatchId","receivedAt","items"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"ownerId":{"type":"integer","minimum":0},"messageBatchId":{"type":"string","minLength":1,"maxLength":64},"receivedAt":{"type":"string","format":"date-time"},"albumTitleText":{"type":"string","maxLength":500},"items":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"object","required":["url","fileName"],"properties":{"url":{"type":"string","pattern":"^https?://","maxLength":4096},"fileName":{"type":"string","minLength":1,"maxLength":180}},"additionalProperties":false}},"maxBytesPerItem":{"type":"integer","minimum":1,"maximum":21474836480},"albumMediaThreshold":{"type":"integer","minimum":1,"maximum":100}} ,"additionalProperties":false}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_message_url_batch';
