ALTER TABLE automation_action
    ADD (
        poll_failure_attempts INT NOT NULL DEFAULT 0
    );
