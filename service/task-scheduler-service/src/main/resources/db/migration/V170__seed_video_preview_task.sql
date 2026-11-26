-- 仅复用抽帧与登记步骤，补齐封面不调用标签或描述模型。
INSERT INTO task_definition (id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,result_schema,child_aggregation_policy_json,version,created_at,updated_at)
SELECT '4c0a873a-9038-4905-ae2b-f3adf16d0cc1','media_generate_video_preview','Generate and register a missing video thumbnail without model inference',task_type,900,cluster_id,NULL,cron_timezone,execution_mode,TRUE,2,overlap_policy,misfire_policy,parameter_schema,result_schema,child_aggregation_policy_json,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM task_definition WHERE name='media_analyze_video';
INSERT INTO task_step_definition (id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at)
SELECT UUID(),'4c0a873a-9038-4905-ae2b-f3adf16d0cc1',name,description,step_kind,script_package,script_version,entrypoint,arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM task_step_definition WHERE task_definition_id=(SELECT id FROM task_definition WHERE name='media_analyze_video')
AND name IN ('begin_analysis','materialize_input','probe','generate_thumbnail','register_thumbnail','commit_analysis','analysis_failure','analysis_timeout','analysis_cancel');
