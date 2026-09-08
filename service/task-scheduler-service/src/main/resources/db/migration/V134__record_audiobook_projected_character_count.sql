UPDATE task_definition
SET description = 'Freeze normalized chapter text and its Unicode character cost baseline for one managed audiobook generation',
    result_schema = '{"type":"object","required":["generationId","projectedChapterCount","projectedCharacterCount"],"properties":{"generationId":{"type":"string","format":"uuid"},"projectedChapterCount":{"type":"integer","minimum":1},"projectedCharacterCount":{"type":"integer","minimum":1}},"additionalProperties":false}',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'reader_generate_audiobook';

UPDATE task_step_definition
SET description = 'Project and durably freeze normalized chapter text with a Unicode character cost baseline',
    updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = '00000000-0000-4000-8000-000000000570'
  AND name = 'extract_audiobook_text';
