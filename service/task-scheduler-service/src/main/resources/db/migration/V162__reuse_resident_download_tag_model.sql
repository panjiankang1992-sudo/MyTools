-- 只升级下载及独立标签任务，复用同一已驻留模型，保留视频工作负载版本。
UPDATE task_definition SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE name IN ('download_http_asset', 'download_message_attachment',
               'download_remote_storage_object', 'download_storage_object', 'media_generate_tags')
  AND id IN (SELECT task_definition_id FROM task_step_definition
             WHERE script_package = 'media_generate_tags' AND script_version = '1.4.0')
ORDER BY version DESC;

UPDATE task_step_definition SET script_version = '1.4.1', updated_at = CURRENT_TIMESTAMP
WHERE script_package = 'media_generate_tags' AND script_version = '1.4.0'
  AND task_definition_id IN (SELECT id FROM task_definition
      WHERE name IN ('download_http_asset', 'download_message_attachment',
                    'download_remote_storage_object', 'download_storage_object', 'media_generate_tags'));
