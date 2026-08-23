ALTER TABLE task_instance ADD COLUMN required_node_labels_json TEXT NOT NULL DEFAULT '{}';
