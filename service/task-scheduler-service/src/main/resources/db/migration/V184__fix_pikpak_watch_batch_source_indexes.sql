-- 多文件监听批次按快照顺序为每个文件分配独立来源序号。
UPDATE task_step_definition
SET script_version = '1.1.0', updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = '00000000-0000-4000-8000-000000000901'
  AND script_package = 'download_pikpak_watch_batch'
  AND script_version = '1.0.0';

UPDATE task_definition
SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000901';
