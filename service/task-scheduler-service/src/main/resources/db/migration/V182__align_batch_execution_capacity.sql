-- 用户主页子任务允许执行全天，入口原三十五分钟限制不能先于后继任务结束。
-- 此处只调整领取后的执行预算，不对排队等待增加到期限制。
UPDATE task_definition
SET timeout_seconds=90000,version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE name='download_message_url_batch' AND timeout_seconds<90000;
UPDATE task_step_definition
SET timeout_seconds=90000,updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_message_url_batch' AND timeout_seconds<90000;

-- 调度器要求父任务集群预留三层后继容量；实际入口并发仍由定义限制为二。
-- 五是二条入口加三层容量校验预算，并非五条入口并发。
UPDATE execution_cluster SET max_concurrent_tasks=5,updated_at=CURRENT_TIMESTAMP
WHERE id='a1810000-0000-4000-8000-000000000001';
