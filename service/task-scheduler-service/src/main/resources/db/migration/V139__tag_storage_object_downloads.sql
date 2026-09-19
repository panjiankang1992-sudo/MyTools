INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,
    created_at,updated_at
) SELECT
    'b1655660-602c-45d2-8ae9-128c99014023',definition.id,
    'generate_tags','Generate automatic tags for the copied storage object','NORMAL',
    'media_generate_tags','1.0.0','scripts/main.py','[]',TRUE,180,'IGNORE',40,1,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM task_definition definition
WHERE definition.name = 'download_storage_object'
  AND NOT EXISTS (
      SELECT 1 FROM task_step_definition step_definition
      WHERE step_definition.task_definition_id = definition.id
        AND step_definition.name = 'generate_tags'
  );

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,
    created_at,updated_at
) SELECT
    'd50ff94e-fb24-4219-a05a-c566ad62bf80',definition.id,
    'record_tags','Persist terminal copied-object tags','NORMAL',
    'download_record_tags','1.0.0','scripts/main.py','[]',TRUE,30,'FAIL_TASK',50,3,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM task_definition definition
WHERE definition.name = 'download_storage_object'
  AND NOT EXISTS (
      SELECT 1 FROM task_step_definition step_definition
      WHERE step_definition.task_definition_id = definition.id
        AND step_definition.name = 'record_tags'
  );

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,
    created_at,updated_at
) SELECT
    '55b43751-c384-4666-a0a2-284bbc5f5f53',definition.id,
    'generate_tags','Generate automatic tags for the copied remote storage object','NORMAL',
    'media_generate_tags','1.0.0','scripts/main.py','[]',TRUE,180,'IGNORE',40,1,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM task_definition definition
WHERE definition.name = 'download_remote_storage_object'
  AND NOT EXISTS (
      SELECT 1 FROM task_step_definition step_definition
      WHERE step_definition.task_definition_id = definition.id
        AND step_definition.name = 'generate_tags'
  );

INSERT INTO task_step_definition (
    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,
    created_at,updated_at
) SELECT
    'a30aaa11-3032-4f47-bbb2-bcfef6261297',definition.id,
    'record_tags','Persist terminal copied remote-object tags','NORMAL',
    'download_record_tags','1.0.0','scripts/main.py','[]',TRUE,30,'FAIL_TASK',50,3,
    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM task_definition definition
WHERE definition.name = 'download_remote_storage_object'
  AND NOT EXISTS (
      SELECT 1 FROM task_step_definition step_definition
      WHERE step_definition.task_definition_id = definition.id
        AND step_definition.name = 'record_tags'
  );

UPDATE task_step_definition
SET description = 'Generate automatic tags for the copied storage object',
    step_kind = 'NORMAL', script_package = 'media_generate_tags', script_version = '1.0.0',
    entrypoint = 'scripts/main.py', arguments_template = '[]', enabled = TRUE,
    timeout_seconds = 180, failure_policy = 'IGNORE', sequence_number = 40,
    max_attempts = 1, updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = (
        SELECT id FROM task_definition
        WHERE name = 'download_storage_object'
    )
  AND name = 'generate_tags';

UPDATE task_step_definition
SET description = 'Persist terminal copied-object tags',
    step_kind = 'NORMAL', script_package = 'download_record_tags', script_version = '1.0.0',
    entrypoint = 'scripts/main.py', arguments_template = '[]', enabled = TRUE,
    timeout_seconds = 30, failure_policy = 'FAIL_TASK', sequence_number = 50,
    max_attempts = 3, updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = (
        SELECT id FROM task_definition
        WHERE name = 'download_storage_object'
    )
  AND name = 'record_tags';

UPDATE task_step_definition
SET description = 'Generate automatic tags for the copied remote storage object',
    step_kind = 'NORMAL', script_package = 'media_generate_tags', script_version = '1.0.0',
    entrypoint = 'scripts/main.py', arguments_template = '[]', enabled = TRUE,
    timeout_seconds = 180, failure_policy = 'IGNORE', sequence_number = 40,
    max_attempts = 1, updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = (
        SELECT id FROM task_definition
        WHERE name = 'download_remote_storage_object'
    )
  AND name = 'generate_tags';

UPDATE task_step_definition
SET description = 'Persist terminal copied remote-object tags',
    step_kind = 'NORMAL', script_package = 'download_record_tags', script_version = '1.0.0',
    entrypoint = 'scripts/main.py', arguments_template = '[]', enabled = TRUE,
    timeout_seconds = 30, failure_policy = 'FAIL_TASK', sequence_number = 50,
    max_attempts = 3, updated_at = CURRENT_TIMESTAMP
WHERE task_definition_id = (
        SELECT id FROM task_definition
        WHERE name = 'download_remote_storage_object'
    )
  AND name = 'record_tags';

UPDATE task_definition
SET timeout_seconds = 2100, version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_storage_object'
  AND timeout_seconds = 1800;

UPDATE task_definition
SET timeout_seconds = 7500, version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE name = 'download_remote_storage_object'
  AND timeout_seconds = 7200;
