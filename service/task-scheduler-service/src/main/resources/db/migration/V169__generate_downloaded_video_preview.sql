-- 下载视频登记媒体库后，幂等触发已有分析任务生成派生封面。
UPDATE task_step_definition SET script_version='1.1.0',updated_at=CURRENT_TIMESTAMP
WHERE script_package='media_register_downloaded_item' AND script_version='1.0.0';
UPDATE task_definition SET version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE id IN (SELECT task_definition_id FROM task_step_definition
WHERE script_package='media_register_downloaded_item' AND script_version='1.1.0');
-- 当前只有两条本次封面修复任务且无定时任务时，恢复有限执行容量。
UPDATE task_definition SET max_concurrency=2,version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE name='media_analyze_video' AND enabled=TRUE AND max_concurrency=0 AND cron_expression IS NULL
AND NOT EXISTS (SELECT 1 FROM task_instance WHERE task_name='media_analyze_video'
AND status IN ('PENDING','ACCEPTED','QUEUED','RUNNING','CANCELLING')
AND id NOT IN ('05652957-5fff-4322-977e-715cd04782cf','b801899b-2201-4450-aafd-c40485ad2310'));
