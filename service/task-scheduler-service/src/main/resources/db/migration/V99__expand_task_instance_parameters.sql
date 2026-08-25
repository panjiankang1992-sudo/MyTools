-- 脚本任务需要保存书源规则等较大的不可变执行快照。
ALTER TABLE task_instance
    MODIFY COLUMN parameters_json MEDIUMTEXT NOT NULL;
