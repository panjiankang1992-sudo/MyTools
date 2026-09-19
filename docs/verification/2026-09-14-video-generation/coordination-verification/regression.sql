SELECT '=== GPU-bearing step outcomes since 14:00 today ===' AS section;
SELECT td.name AS task_name, ts.script_package, se.status, COUNT(*) AS n
FROM step_execution se
JOIN task_execution te ON te.id = se.task_execution_id
JOIN task_instance ti ON ti.id = te.task_instance_id
JOIN task_definition td ON td.id = ti.task_definition_id
JOIN task_step_definition ts ON ts.id = se.step_definition_id
WHERE se.created_at >= '2026-09-14 14:00:00'
  AND ts.script_package IN ('image_generate','media_generate_tags')
GROUP BY td.name, ts.script_package, se.status
ORDER BY td.name, se.status;

SELECT '=== any GPU-coordination / lock errors today ===' AS section;
SELECT se.error_code, COUNT(*) AS n
FROM step_execution se
WHERE se.created_at >= '2026-09-14 00:00:00'
  AND se.error_code IS NOT NULL
GROUP BY se.error_code ORDER BY n DESC LIMIT 20;

SELECT '=== image_generate outcomes today ===' AS section;
SELECT se.status, COUNT(*) AS n, MIN(se.created_at) AS first_seen, MAX(se.finished_at) AS last_finished
FROM step_execution se
JOIN task_step_definition ts ON ts.id = se.step_definition_id
WHERE ts.script_package = 'image_generate' AND se.created_at >= '2026-09-14 00:00:00'
GROUP BY se.status;
