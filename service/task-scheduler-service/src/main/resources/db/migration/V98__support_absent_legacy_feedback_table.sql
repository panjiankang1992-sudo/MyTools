UPDATE task_definition
SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE name = 'feedback_migrate_legacy';

UPDATE task_step_definition
SET script_version = '1.2.0', updated_at = CURRENT_TIMESTAMP
WHERE script_package = 'feedback_migrate_legacy'
  AND name = 'migrate_feedback';
