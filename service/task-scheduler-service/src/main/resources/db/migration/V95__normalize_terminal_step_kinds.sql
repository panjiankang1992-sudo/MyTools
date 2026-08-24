UPDATE task_step_definition SET step_kind='ON_FAILURE',updated_at=CURRENT_TIMESTAMP WHERE step_kind='FAILURE';
UPDATE task_step_definition SET step_kind='ON_TIMEOUT',updated_at=CURRENT_TIMESTAMP WHERE step_kind='TIMEOUT';
UPDATE task_step_definition SET step_kind='ON_CANCEL',updated_at=CURRENT_TIMESTAMP WHERE step_kind='CANCEL';
