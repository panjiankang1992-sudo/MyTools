-- 新版本支持免密钥地区查询，保留新闻任务和集群原有启停状态。
UPDATE task_step_definition
SET script_version='1.2.0', updated_at=CURRENT_TIMESTAMP
WHERE id='e6e547db-237e-4ad4-aeda-3c84cde642e0'
  AND script_package='news_resolve_region' AND script_version='1.1.0';
