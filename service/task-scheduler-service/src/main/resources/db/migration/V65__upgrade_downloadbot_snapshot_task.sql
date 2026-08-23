UPDATE task_definition
SET description = 'Capture DownloadBot assets, message sources, and link history in one consistent read-only snapshot',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000398'
  AND name = 'downloadbot_capture_snapshot';

UPDATE task_step_definition
SET description = 'Capture and atomically seal one bounded legacy snapshot including message asset sources',
    script_version = '1.1.0',
    updated_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000510'
  AND task_definition_id = '00000000-0000-4000-8000-000000000398'
  AND name = 'capture_snapshot';
