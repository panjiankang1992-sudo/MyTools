UPDATE task_definition SET
    description='Run and atomically commit versioned media intelligence with durable derived assets',
    timeout_seconds=900,
    parameter_schema='{"type":"object","required":["mediaItemId","assetRegistryId","assetId","analysisVersion","sourcePath","contentSha256","ownerId","filename","mimeType"],"properties":{"mediaItemId":{"type":"string","format":"uuid"},"assetRegistryId":{"type":"string","format":"uuid"},"assetId":{"type":"string","minLength":1,"maxLength":255},"analysisVersion":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,64}$"},"sourcePath":{"type":"string","minLength":1,"maxLength":4096},"contentSha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"ownerId":{"type":"integer","minimum":0},"filename":{"type":"string","minLength":1,"maxLength":512},"mimeType":{"type":"string","minLength":1,"maxLength":255},"assetMimeType":{"type":"string","minLength":1,"maxLength":255},"storageRoot":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9._-]{0,127}$"},"frameCount":{"type":"integer","minimum":1,"maximum":12},"seekSeconds":{"type":"number","minimum":0}},"additionalProperties":false}',
    result_schema='{"type":"object","required":["mediaItemId","status","tagCount","artifactCount"]}',
    version=2,updated_at=CURRENT_TIMESTAMP
WHERE id='00000000-0000-4000-8000-000000000305';

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES
('00000000-0000-4000-8000-000000000498','00000000-0000-4000-8000-000000000305','begin_analysis','Bind the task and analysis version before expensive work','NORMAL','media_begin_analysis','1.0.0','scripts/main.py','[]',TRUE,30,'FAIL_TASK',5,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000499','00000000-0000-4000-8000-000000000305','generate_thumbnail','Generate the versioned primary thumbnail','NORMAL','media_generate_thumbnail','1.0.0','scripts/main.py','[]',TRUE,45,'FAIL_TASK',15,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000500','00000000-0000-4000-8000-000000000305','register_thumbnail','Publish and register the primary thumbnail','NORMAL','asset_register_media_thumbnail','1.0.0','scripts/main.py','[]',TRUE,90,'FAIL_TASK',18,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000501','00000000-0000-4000-8000-000000000305','register_storyboard','Publish and register every storyboard frame','NORMAL','asset_register_media_storyboard','1.0.0','scripts/main.py','[]',TRUE,300,'FAIL_TASK',25,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000502','00000000-0000-4000-8000-000000000305','generate_tags','Generate optional versioned media tags','NORMAL','media_generate_tags','1.0.0','scripts/main.py','[]',TRUE,180,'IGNORE',28,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000503','00000000-0000-4000-8000-000000000305','commit_analysis','Atomically commit tags, description, and derived asset identities','NORMAL','media_commit_analysis','1.0.0','scripts/main.py','[]',TRUE,30,'FAIL_TASK',40,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000504','00000000-0000-4000-8000-000000000305','analysis_failure','Record failed analysis terminal state','ON_FAILURE','media_fail_analysis','1.0.0','scripts/main.py','[]',TRUE,30,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000505','00000000-0000-4000-8000-000000000305','analysis_timeout','Record timed out analysis terminal state','ON_TIMEOUT','media_fail_analysis','1.0.0','scripts/main.py','[]',TRUE,30,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-4000-8000-000000000506','00000000-0000-4000-8000-000000000305','analysis_cancel','Record cancelled analysis terminal state','ON_CANCEL','media_fail_analysis','1.0.0','scripts/main.py','[]',TRUE,30,'IGNORE',10,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
