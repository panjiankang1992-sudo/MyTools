-- 子任务只使用领取后的执行期限，消息编排仍受父任务总执行期限约束。
UPDATE task_definition
SET version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE name='download_message_url_batch'
  AND id IN (SELECT task_definition_id FROM task_step_definition
             WHERE script_package='download_message_url_batch' AND script_version='1.1.0');

UPDATE task_step_definition
SET script_version='1.2.0',updated_at=CURRENT_TIMESTAMP
WHERE script_package='download_message_url_batch' AND script_version='1.1.0'
  AND task_definition_id IN (SELECT id FROM task_definition WHERE name='download_message_url_batch');
