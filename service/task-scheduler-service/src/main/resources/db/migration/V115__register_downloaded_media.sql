INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,
    created_at,updated_at
) VALUES
('5ac2a154-51fc-41c0-99c9-39f0d754f85c','00000000-0000-4000-8000-000000000304',
 'register_media_item','Project a completed HTTP media download into Media Library','NORMAL',
 'media_register_downloaded_item','1.0.0','scripts/main.py','[]',TRUE,60,'FAIL_TASK',25,3,
 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('ccb36abd-8f58-48fc-b4e1-50b39141876b','00000000-0000-4000-8000-000000000407',
 'register_media_item','Project a completed attachment media download into Media Library','NORMAL',
 'media_register_downloaded_item','1.0.0','scripts/main.py','[]',TRUE,60,'FAIL_TASK',25,3,
 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

UPDATE task_definition
SET description=CONCAT(description,' and project media into Media Library'),
    version=version+1,
    updated_at=CURRENT_TIMESTAMP
WHERE id IN ('00000000-0000-4000-8000-000000000304',
             '00000000-0000-4000-8000-000000000407');
