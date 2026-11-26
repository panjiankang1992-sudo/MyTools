-- 消息入口与帖子解析分离，避免入口等待用户任务时挤占其后继任务的集群容量。
INSERT INTO execution_cluster
    (id,name,description,dispatch_strategy,max_concurrent_tasks,labels_json,enabled,created_at,updated_at)
SELECT 'a1810000-0000-4000-8000-000000000001','download-message-batches',
       'Bounded message batch parents isolated from post resolvers',dispatch_strategy,2,labels_json,
       enabled,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM execution_cluster WHERE id=(SELECT cluster_id FROM task_definition WHERE name='download_message_url_batch');
INSERT INTO cluster_node (cluster_id,node_id,weight,priority,enabled,joined_at)
SELECT 'a1810000-0000-4000-8000-000000000001',node_id,weight,priority,enabled,CURRENT_TIMESTAMP
FROM cluster_node WHERE cluster_id=(SELECT cluster_id FROM task_definition WHERE name='download_message_url_batch');

-- 入口最多同时处理两条消息，站点用户和帖子级并发保持原上限。
UPDATE task_definition
SET cluster_id='a1810000-0000-4000-8000-000000000001',max_concurrency=2,
    version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE name='download_message_url_batch';
UPDATE task_step_definition SET script_version='1.1.0',updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_message_url_batch' AND script_version='1.0.0';

-- 新任务按四条在途帖子滚动补位；原包保留给既有执行和回退。
UPDATE task_definition SET version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE id IN (SELECT task_definition_id FROM task_step_definition
             WHERE script_package='download_resolve_x_user' AND script_version='1.1.1');
UPDATE task_step_definition SET script_version='1.2.0',updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_resolve_x_user' AND script_version='1.1.1';
