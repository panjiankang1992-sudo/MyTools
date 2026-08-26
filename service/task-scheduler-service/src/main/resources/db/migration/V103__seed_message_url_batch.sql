-- 一条消息先把每个 URL 解析为原子媒体清单，再按整批数量决定统一目录。
INSERT INTO task_definition (
    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
    result_schema,version,created_at,updated_at
) VALUES
('a1030000-0000-4000-8000-000000000001','download_resolve_x_url',
 'Resolve one X URL without downloading media','IMMEDIATE',180,
 '00000000-0000-4000-8000-000000000010',NULL,NULL,'SINGLE_NODE',TRUE,3,'SKIP','IGNORE',
 '{"type":"object","required":["url","resolveOnly"],"properties":{"url":{"type":"string","pattern":"^https?://"},"maxMedia":{"type":"integer","minimum":1,"maximum":40},"resolveOnly":{"const":true}},"additionalProperties":false}',
 '{"type":"object","required":["tweetId","resources","mediaCount"],"properties":{"tweetId":{"type":"string","pattern":"^[0-9]{1,24}$"},"resources":{"type":"array","minItems":1,"maxItems":40},"mediaCount":{"type":"integer","minimum":1,"maximum":40}},"additionalProperties":false}',
 1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('a1030000-0000-4000-8000-000000000002','download_message_url_batch',
 'Resolve all URLs from one message, choose one directory, and wait for atomic media downloads',
 'IMMEDIATE',2100,'00000000-0000-4000-8000-000000000010',NULL,NULL,'SINGLE_NODE',TRUE,1,
 'SKIP','IGNORE',
 '{"type":"object","required":["downloadRequestId","ownerId","messageBatchId","receivedAt","items"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"ownerId":{"type":"integer","minimum":0},"messageBatchId":{"type":"string","minLength":1,"maxLength":64},"receivedAt":{"type":"string","format":"date-time"},"items":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"object","required":["url","fileName"],"properties":{"url":{"type":"string","pattern":"^https?://","maxLength":4096},"fileName":{"type":"string","minLength":1,"maxLength":180}},"additionalProperties":false}},"maxBytesPerItem":{"type":"integer","minimum":1,"maximum":21474836480},"albumMediaThreshold":{"type":"integer","minimum":1,"maximum":100}},"additionalProperties":false}',
 '{"type":"object","required":["requestId","inputCount","mediaCount","albumFolder","resolverTaskIds","downloadTaskIds"]}',
 1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES
('a1030000-0000-4000-8000-000000000011','a1030000-0000-4000-8000-000000000001',
 'resolve_x_url','Resolve one X post into a bounded media list','NORMAL',
 'download_resolve_x_post','1.0.0','scripts/main.py','[]',TRUE,180,'FAIL_TASK',10,2,
 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('a1030000-0000-4000-8000-000000000012','a1030000-0000-4000-8000-000000000002',
 'orchestrate_message_urls','Resolve the whole message before creating atomic downloads','NORMAL',
 'download_message_url_batch','1.0.0','scripts/main.py','[]',TRUE,2100,'FAIL_TASK',10,1,
 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
