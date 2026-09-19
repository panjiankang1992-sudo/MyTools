SELECT '=== task activity in the last 3 hours (all tasks) ===' AS section;
SELECT status, COUNT(*) AS n FROM task_instance
WHERE created_at >= NOW() - INTERVAL 3 HOUR GROUP BY status ORDER BY n DESC;

SELECT '=== most recent task instances ===' AS section;
SELECT ti.task_name, ti.status, ti.created_at, ti.started_at
FROM task_instance ti ORDER BY ti.created_at DESC LIMIT 8;

SELECT '=== GPU-bearing definitions: enabled / cron / concurrency ===' AS section;
SELECT DISTINCT td.name, td.enabled, td.max_concurrency, td.execution_mode, td.cron_expression
FROM task_definition td JOIN task_step_definition ts ON ts.task_definition_id = td.id
WHERE ts.script_package IN ('image_generate','media_generate_tags')
ORDER BY td.name;

SELECT '=== last GPU-bearing step executions (any time) ===' AS section;
SELECT td.name, ts.script_package, se.status, se.created_at
FROM step_execution se
JOIN task_execution te ON te.id = se.task_execution_id
JOIN task_instance ti ON ti.id = te.task_instance_id
JOIN task_definition td ON td.id = ti.task_definition_id
JOIN task_step_definition ts ON ts.id = se.step_definition_id
WHERE ts.script_package IN ('image_generate','media_generate_tags')
ORDER BY se.created_at DESC LIMIT 6;
