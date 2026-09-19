-- 远端下载传递真实媒体类型，补跑只处理未完成文件并保留成功标签。
UPDATE task_step_definition
SET script_version='1.3.0', updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_remote_storage_object';

UPDATE task_step_definition
SET script_version='1.4.0', updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_pikpak_magnet';

UPDATE task_definition
SET version=version+1, updated_at=CURRENT_TIMESTAMP
WHERE name IN ('download_remote_storage_object','download_pikpak_magnet');
