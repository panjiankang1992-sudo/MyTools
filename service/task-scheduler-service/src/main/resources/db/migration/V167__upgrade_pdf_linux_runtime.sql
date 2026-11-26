-- 固定 Linux 沙箱修复的不可变版本，保留旧包供历史任务追溯。
UPDATE task_step_definition SET script_version='1.0.1',updated_at=CURRENT_TIMESTAMP WHERE task_definition_id='6b4b3584-e955-4289-9558-6b3a3152422d' AND script_package='pdf_process' AND script_version='1.0.0';
