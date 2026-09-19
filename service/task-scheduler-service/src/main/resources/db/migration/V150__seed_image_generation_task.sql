-- GPU 协调与本机工作流验收后再通过管理 API 显式启用集群和任务。
INSERT INTO execution_cluster (
    id, name, description, dispatch_strategy, max_concurrent_tasks, labels_json, enabled, created_at, updated_at
) VALUES (
    'a13bb181-55e4-41d5-ab17-68ed4626123f', 'image-generation',
    'Dedicated serialized image generation hosts', 'LEAST_RUNNING', 1,
    '{"image.generation":"enabled"}', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, child_aggregation_policy_json, version, created_at, updated_at
) VALUES (
    'bc4024f1-2062-440f-aed1-a50b9b6f6650', 'image_generate',
    'Generate images through a validated local workflow', 'IMMEDIATE', 2700,
    'a13bb181-55e4-41d5-ab17-68ed4626123f', NULL, NULL, 'SINGLE_NODE', FALSE, 1, 'QUEUE', 'IGNORE',
    '{"type":"object","required":["jobId","resourceId","prompt","mode","size","count","seed","references","workflowRevision"],"properties":{"jobId":{"type":"string","format":"uuid"},"resourceId":{"enum":["krea2-local","sillytraven-remote"]},"prompt":{"type":"string","minLength":1,"maxLength":4000},"mode":{"enum":["TEXT_TO_IMAGE","STYLE_REFERENCE"]},"size":{"enum":["1024x1024","832x1216","1216x832"]},"count":{"enum":[1,2,4]},"seed":{"type":"integer","minimum":0,"maximum":2147483647},"references":{"type":"array","maxItems":2,"items":{"type":"string","format":"uuid"}},"workflowRevision":{"type":"string","maxLength":100},"modelId":{"type":"string"},"idempotencyKey":{"type":"string"}},"additionalProperties":false}',
    '{"type":"object","required":["indices","workflowRevision"],"properties":{"indices":{"type":"array","maxItems":4,"items":{"type":"integer","minimum":0,"maximum":3}},"workflowRevision":{"type":"string"}},"additionalProperties":false}',
    '{"strategy":"ALL_SUCCESS","minSuccessCount":null}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

-- 禁止自动重试模型提交；网络歧义由持久 prompt_id 对账。
INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts, created_at, updated_at
) VALUES (
    '8327e385-fbe4-4e54-97c3-9b0c2921dd16', 'bc4024f1-2062-440f-aed1-a50b9b6f6650',
    'generate', 'Execute a pinned local image workflow', 'NORMAL', 'image_generate', '1.0.0',
    'scripts/main.py', '[]', TRUE, 2700, 'FAIL_TASK', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
