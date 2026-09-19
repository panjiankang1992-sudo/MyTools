-- 视频任务与图片任务一样，只有在显存协调与本机工作流验收通过后才由管理 API 显式启用。
-- 集群并发固定为 1：单卡显存只够一个 480P 视频任务，串行化由调度层与 GPU 锁双重保证。
INSERT INTO execution_cluster (
    id, name, description, dispatch_strategy, max_concurrent_tasks, labels_json, enabled, created_at, updated_at
) VALUES (
    '48615d43-0768-4d04-aa73-78fc97f57e47', 'video-generation',
    'Dedicated serialized video generation hosts', 'LEAST_RUNNING', 1,
    '{"video.generation":"enabled"}', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO task_definition (
    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression, cron_timezone,
    execution_mode, enabled, max_concurrency, overlap_policy, misfire_policy, parameter_schema,
    result_schema, child_aggregation_policy_json, version, created_at, updated_at
) VALUES (
    '44544153-c55b-4124-8d7e-0365f70c3893', 'video_generate',
    'Generate short videos through a validated local VACE workflow', 'IMMEDIATE', 3600,
    '48615d43-0768-4d04-aa73-78fc97f57e47', NULL, NULL, 'SINGLE_NODE', FALSE, 1, 'QUEUE', 'IGNORE',
    '{"type":"object","required":["jobId","resourceId","mode","prompt","seed","width","height","output","resolvedInputs","workflowRevision","audioPolicy","modelRevision"],"properties":{"jobId":{"type":"string","format":"uuid"},"resourceId":{"enum":["wan-vace-1.3b-local"]},"mode":{"enum":["TEXT_TO_VIDEO","FIRST_FRAME","SUBJECT_REFERENCES","FIRST_LAST_FRAMES","STRUCTURE_RESTYLE","MASKED_EDIT"]},"prompt":{"type":"string","minLength":1,"maxLength":4000},"seed":{"type":"integer","minimum":0,"maximum":2147483647},"width":{"enum":[832]},"height":{"enum":[480]},"output":{"type":"object","required":["size","frames","fps"],"properties":{"size":{"enum":["832x480"]},"frames":{"enum":[49]},"fps":{"enum":[16]}},"additionalProperties":false},"resolvedInputs":{"type":"array","maxItems":2,"items":{"type":"object","required":["inputId","uploadId","role","sha256"],"properties":{"inputId":{"type":"string","format":"uuid"},"uploadId":{"type":"string","format":"uuid"},"role":{"enum":["SUBJECT","FIRST_FRAME","LAST_FRAME","SOURCE_VIDEO","MASK_VIDEO"]},"sha256":{"type":"string","pattern":"^[0-9a-f]{64}$"},"trimStartMs":{"type":"integer","minimum":0},"trimEndMs":{"type":"integer","minimum":0}},"additionalProperties":false}},"workflowRevision":{"type":"string","maxLength":100},"audioPolicy":{"enum":["SILENT"]},"modelRevision":{"type":"string","maxLength":100},"idempotencyKey":{"type":"string","maxLength":200}},"additionalProperties":false}',
    '{"type":"object","required":["frames","fps","width","height","durationMs","workflowRevision","controlSha256"],"properties":{"frames":{"type":"integer","minimum":1,"maximum":49},"fps":{"type":"integer","minimum":16,"maximum":16},"width":{"type":"integer","minimum":832,"maximum":832},"height":{"type":"integer","minimum":480,"maximum":480},"durationMs":{"type":"integer","minimum":1},"workflowRevision":{"type":"string"},"controlSha256":{"type":"string"},"outputSha256":{"type":"string"},"coverSha256":{"type":"string"},"referenceFill":{"type":"string"},"fillBlend":{"type":"number"},"sourceQuality":{"type":"object"},"promptId":{"type":"string"},"inferenceMillis":{"type":"integer","minimum":0},"resourcePeak":{"type":"object"}},"additionalProperties":false}',
    '{"strategy":"ALL_SUCCESS","minSuccessCount":null}', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

-- 提交响应丢失时不能自动重试：视频推理成本高，重复提交会白烧一个窗口。
INSERT INTO task_step_definition (
    id, task_definition_id, name, description, step_kind, script_package, script_version, entrypoint,
    arguments_template, enabled, timeout_seconds, failure_policy, sequence_number, max_attempts, created_at, updated_at
) VALUES (
    'c776c322-003d-4eab-b5bc-6460761b0801', '44544153-c55b-4124-8d7e-0365f70c3893',
    'generate', 'Execute a pinned local VACE 1.3B workflow', 'NORMAL', 'video_generate', '1.0.0',
    'scripts/main.py', '[]', TRUE, 3600, 'FAIL_TASK', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
