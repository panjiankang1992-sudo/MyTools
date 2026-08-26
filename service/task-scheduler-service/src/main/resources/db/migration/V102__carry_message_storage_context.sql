-- X 链接父任务需要把消息批次和接收时间传递给每个原子媒体任务。
UPDATE task_definition
SET parameter_schema = '{"type":"object","required":["downloadRequestId","url"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"url":{"type":"string","pattern":"^https?://"},"maxMedia":{"type":"integer","minimum":1,"maximum":40},"maxBytesPerItem":{"type":"integer","minimum":1,"maximum":21474836480},"albumMediaThreshold":{"type":"integer","minimum":1,"maximum":100},"ownerId":{"type":"integer","minimum":0},"messageBatchId":{"type":"string","maxLength":64},"receivedAt":{"type":"string","format":"date-time"}},"additionalProperties":false}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000401';
