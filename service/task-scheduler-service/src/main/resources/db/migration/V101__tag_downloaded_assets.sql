UPDATE task_definition
SET timeout_seconds = 2100, version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN ('00000000-0000-4000-8000-000000000304',
             '00000000-0000-4000-8000-000000000407');

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,created_at,updated_at
) VALUES
('de814c1f-33bc-412a-80a0-48562db94abd','00000000-0000-4000-8000-000000000304',
 'generate_thumbnail','Generate bounded visual input for downloaded media','NORMAL',
 'media_generate_thumbnail','1.0.0','scripts/main.py','[]',TRUE,45,'IGNORE',35,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('cd5570e5-da90-436e-b942-7f40e689ae41','00000000-0000-4000-8000-000000000304',
 'generate_tags','Generate automatic tags for the downloaded file','NORMAL',
 'media_generate_tags','1.0.0','scripts/main.py','[]',TRUE,180,'IGNORE',40,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('ef1411d3-12ca-457e-b428-3fb305adf207','00000000-0000-4000-8000-000000000304',
 'record_tags','Persist terminal downloaded-file tags','NORMAL',
 'download_record_tags','1.0.0','scripts/main.py','[]',TRUE,30,'FAIL_TASK',50,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('1b4eec92-c89b-4de1-bb87-f73177b9536f','00000000-0000-4000-8000-000000000407',
 'generate_thumbnail','Generate bounded visual input for downloaded attachment media','NORMAL',
 'media_generate_thumbnail','1.0.0','scripts/main.py','[]',TRUE,45,'IGNORE',35,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('52e8c61f-9939-4282-be5c-266000d3b19b','00000000-0000-4000-8000-000000000407',
 'generate_tags','Generate automatic tags for the downloaded attachment','NORMAL',
 'media_generate_tags','1.0.0','scripts/main.py','[]',TRUE,180,'IGNORE',40,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('8480a03d-994e-43db-ba3a-f942d259cad9','00000000-0000-4000-8000-000000000407',
 'record_tags','Persist terminal downloaded-attachment tags','NORMAL',
 'download_record_tags','1.0.0','scripts/main.py','[]',TRUE,30,'FAIL_TASK',50,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
