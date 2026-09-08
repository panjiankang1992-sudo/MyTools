ALTER TABLE step_execution
    ADD COLUMN report_request_id VARCHAR(64) NULL;

CREATE UNIQUE INDEX uk_step_execution_report_request
    ON step_execution(report_request_id);

ALTER TABLE task_execution
    ADD COLUMN completion_request_id VARCHAR(64) NULL;

CREATE UNIQUE INDEX uk_task_execution_completion_request
    ON task_execution(completion_request_id);
