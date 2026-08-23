ALTER TABLE task_definition
    ADD CONSTRAINT fk_task_definition_cluster
    FOREIGN KEY (cluster_id) REFERENCES execution_cluster(id);
