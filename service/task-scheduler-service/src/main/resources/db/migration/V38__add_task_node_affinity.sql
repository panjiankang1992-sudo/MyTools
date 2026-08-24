ALTER TABLE task_instance ADD COLUMN required_node_labels_json TEXT NULL;
UPDATE task_instance SET required_node_labels_json = '{}' WHERE required_node_labels_json IS NULL;
ALTER TABLE task_instance MODIFY COLUMN required_node_labels_json TEXT NOT NULL;
