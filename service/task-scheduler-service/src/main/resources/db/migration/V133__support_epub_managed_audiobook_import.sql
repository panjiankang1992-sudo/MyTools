UPDATE task_definition
SET description = 'Copy one owner-verified TXT or EPUB media item into Reader managed storage and build its catalog',
    parameter_schema = '{"type":"object","required":["requestId","ownerId","sourceId","mediaItemId","mediaAssetId","title","mimeType","sizeBytes","contentSha256","storageRoot"],"properties":{"requestId":{"type":"string","format":"uuid"},"ownerId":{"type":"integer","minimum":1},"sourceId":{"type":"string","format":"uuid"},"mediaItemId":{"type":"string","format":"uuid"},"mediaAssetId":{"type":"string","format":"uuid"},"title":{"type":"string","minLength":1,"maxLength":300},"mimeType":{"enum":["text/plain","application/epub+zip"]},"sizeBytes":{"type":"integer","minimum":1,"maximum":536870912},"contentSha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"storageRoot":{"type":"string","minLength":1,"maxLength":128}},"additionalProperties":false}',
    result_schema = '{"type":"object","required":["requestId","sourceId","title","format","chapterCount","size","sha256","storageUri"],"properties":{"requestId":{"type":"string","format":"uuid"},"sourceId":{"type":"string","format":"uuid"},"title":{"type":"string"},"author":{"type":"string"},"format":{"enum":["TXT","EPUB"]},"chapterCount":{"type":"integer","minimum":1},"size":{"type":"integer","minimum":1},"sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"storageUri":{"type":"string","pattern":"^storage://"}},"additionalProperties":false}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'reader_import_managed_ebook';

UPDATE task_step_definition
SET description = 'Copy and verify one frozen Media Library TXT or EPUB item',
    updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = '00000000-0000-4000-8000-000000000574'
  AND name = 'import_ebook';
