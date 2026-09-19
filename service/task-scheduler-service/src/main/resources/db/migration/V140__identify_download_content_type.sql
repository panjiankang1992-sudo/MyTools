-- 已校验文件头贯穿发布、资产登记和图片标签步骤，旧脚本包保持可回退。
UPDATE task_step_definition
SET script_version = '1.1.0', updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id IN (
    SELECT id FROM task_definition WHERE name IN ('download_http_asset', 'download_message_attachment')
)
AND script_package IN ('download_publish_file', 'asset_register_content', 'media_generate_tags');

UPDATE task_definition SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE name IN ('download_http_asset', 'download_message_attachment');

-- PikPak 内部调用等待应覆盖云端提交期限，云端空结果不得报告成功。
UPDATE task_step_definition
SET script_version = '1.1.0', updated_at = CURRENT_TIMESTAMP
WHERE script_package = 'download_pikpak_magnet';

UPDATE task_definition SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_pikpak_magnet';
