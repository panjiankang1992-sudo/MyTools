-- 后处理独占一个后台槽位；与网络下载、消息对账和编排队列分离。
INSERT INTO execution_cluster (id,name,description,dispatch_strategy,max_concurrent_tasks,labels_json,enabled,created_at,updated_at)
VALUES ('a1800000-0000-4000-8000-000000000001','download-enrichment','Asynchronous verified download enrichment','LEAST_RUNNING',1,'{}',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO cluster_node (cluster_id,node_id,weight,priority,enabled,joined_at)
SELECT 'a1800000-0000-4000-8000-000000000001',cn.node_id,cn.weight,cn.priority,cn.enabled,CURRENT_TIMESTAMP
FROM cluster_node cn JOIN task_definition td ON td.cluster_id=cn.cluster_id WHERE td.name='download_http_asset';

INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,child_aggregation_policy_json,version,created_at,updated_at)
VALUES ('a1800000-0000-4000-8000-000000000002','download_enrich_file','Materialize a verified download and enrich it without blocking download completion','IMMEDIATE',1800,'a1800000-0000-4000-8000-000000000001',NULL,NULL,'SINGLE_NODE',TRUE,1,'SKIP','IGNORE',
'{"type":"object","required":["downloadRequestId","itemId","ownerId","assetRegistryId","assetId","contentSha256","filename","mimeType","mediaRegistered"],"properties":{"downloadRequestId":{"type":"string","format":"uuid"},"itemId":{"type":"string","minLength":1,"maxLength":255},"ownerId":{"type":"integer","minimum":0},"assetRegistryId":{"type":"string","format":"uuid"},"assetId":{"type":"string","format":"uuid"},"contentSha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"filename":{"type":"string","minLength":1,"maxLength":255},"mimeType":{"type":"string","minLength":1,"maxLength":255},"mediaRegistered":{"type":"boolean"}},"additionalProperties":false}',
'{}','{"strategy":"ALL_SUCCESS","minSuccessCount":null}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
VALUES
('a1800000-0000-4000-8000-000000000003','a1800000-0000-4000-8000-000000000002','materialize_input','Verify asset identity and materialize durable storage content','NORMAL','media_materialize_input','1.0.0','scripts/main.py','[]',TRUE,300,'FAIL_TASK',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('a1800000-0000-4000-8000-000000000004','a1800000-0000-4000-8000-000000000002','generate_thumbnail','Generate an optional bounded thumbnail','NORMAL','media_generate_thumbnail','1.0.0','scripts/main.py','[]',TRUE,45,'IGNORE',20,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('a1800000-0000-4000-8000-000000000005','a1800000-0000-4000-8000-000000000002','generate_tags','Generate GPU tags on an independent queue','NORMAL','media_generate_tags','1.4.2','scripts/main.py','[]',TRUE,300,'IGNORE',30,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('a1800000-0000-4000-8000-000000000006','a1800000-0000-4000-8000-000000000002','record_tags','Commit tags and the owner-bound media projection','NORMAL','download_record_tags','1.2.0','scripts/main.py','[]',TRUE,90,'FAIL_TASK',40,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

-- 只有新领取任务使用异步结果协议；已经领取的旧任务保留原步骤和参数。
UPDATE task_definition SET version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE name IN ('download_http_asset','download_message_attachment','download_storage_object','download_remote_storage_object');
UPDATE task_step_definition SET script_version='1.1.0',updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_record_result' AND script_version='1.0.0'
 AND task_definition_id IN (SELECT id FROM task_definition WHERE name IN
 ('download_http_asset','download_message_attachment','download_storage_object','download_remote_storage_object'));
UPDATE task_step_definition SET enabled=FALSE,updated_at=CURRENT_TIMESTAMP
WHERE task_definition_id IN (SELECT id FROM task_definition WHERE name IN
 ('download_http_asset','download_message_attachment','download_storage_object','download_remote_storage_object'))
 AND name IN ('generate_thumbnail','generate_tags','record_tags');

-- 附件提交不再创建占槽轮询任务，原对账定义保留供已存在任务完成和回退。
UPDATE task_definition SET version=version+1,description='Resolve and submit one attachment; lifecycle reconciliation runs in the Download service',updated_at=CURRENT_TIMESTAMP
WHERE name='message_download_attachment';
UPDATE task_step_definition SET script_version='1.2.1',updated_at=CURRENT_TIMESTAMP
WHERE script_package='message_submit_attachment_download' AND script_version='1.1.0';
