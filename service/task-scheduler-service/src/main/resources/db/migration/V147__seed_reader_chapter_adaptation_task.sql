-- 默认关闭的专属集群和任务定义；隔离、授权、模型及同意门禁验收后才能显式启用。
INSERT INTO execution_cluster (
    id, name, description, dispatch_strategy, max_concurrent_tasks, labels_json, enabled, created_at, updated_at
) VALUES (
    'c92ac466-14e2-48b7-b551-bd7a676aa0a7', 'reader-adaptation',
    'Dedicated confined chapter adaptation hosts', 'LEAST_RUNNING', 2,
    '{"reader.adaptation":"enabled"}', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, child_aggregation_policy_json, version, created_at, updated_at
) VALUES (
    'd9c1c5ef-4656-4770-9976-955e93906e93', 'reader_adapt_novel_chapter',
    'Adapt one canonical shelf chapter through the confined host workflow', 'IMMEDIATE', 900,
    'c92ac466-14e2-48b7-b551-bd7a676aa0a7', NULL, NULL, 'SINGLE_NODE', FALSE, 2, 'QUEUE', 'IGNORE',
    '{"type":"object","required":["adaptationId"],"properties":{"adaptationId":{"type":"string","pattern":"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"}},"additionalProperties":false}',
    '{"type":"object","properties":{},"additionalProperties":false}',
    '{"strategy":"ALL_SUCCESS","minSuccessCount":null}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

-- 宿主执行已有 PLAN/GENERATE/CRITIC/REPAIR 状态机，步骤本身不接收正文、意图或任何服务凭据。
INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts, created_at, updated_at
) VALUES (
    '31dfd987-4e7b-4fc1-8d9e-6437d4dde83f', 'd9c1c5ef-4656-4770-9976-955e93906e93',
    'run', 'Invoke the single-use local host broker', 'NORMAL', 'reader_adapt_novel_chapter', '1.0.0',
    'scripts/main.py', '[]', TRUE, 900, 'FAIL_TASK', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
