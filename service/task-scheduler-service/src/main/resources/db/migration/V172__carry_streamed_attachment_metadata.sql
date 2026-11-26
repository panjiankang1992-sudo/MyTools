-- 流式消息附件必须接纳 Messaging 已发送的归档元数据，继续拒绝未知字段和无效大小。
UPDATE task_definition
SET parameter_schema = '{"type":"object","required":["downloadRequestId","attachmentJobId","itemId","fileName","maxBytes","ownerId"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"attachmentJobId":{"type":"string","format":"uuid"},"itemId":{"type":"string","minLength":1,"maxLength":255},"fileName":{"type":"string","minLength":1,"maxLength":255},"maxBytes":{"type":"integer","minimum":1,"maximum":21474836480},"ownerId":{"type":"integer","minimum":1},"resourceUsername":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,128}$"},"assetMimeType":{"type":"string","minLength":1,"maxLength":255},"receivedAt":{"type":"string","format":"date-time"}},"additionalProperties":false}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_message_attachment';
