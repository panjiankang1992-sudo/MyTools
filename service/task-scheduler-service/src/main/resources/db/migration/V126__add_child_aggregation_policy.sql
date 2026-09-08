ALTER TABLE task_definition
    ADD COLUMN child_aggregation_policy_json TEXT;

UPDATE task_definition
SET child_aggregation_policy_json = '{"strategy":"ALL_SUCCESS","minSuccessCount":null}'
WHERE child_aggregation_policy_json IS NULL;

ALTER TABLE task_definition
    MODIFY COLUMN child_aggregation_policy_json TEXT NOT NULL;
