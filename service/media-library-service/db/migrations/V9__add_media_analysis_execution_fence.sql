ALTER TABLE media_analysis ADD COLUMN fencing_task_instance_id CHAR(36) NULL;
ALTER TABLE media_analysis ADD COLUMN fencing_step_name VARCHAR(128) NULL;
ALTER TABLE media_analysis ADD COLUMN fencing_business_key VARCHAR(255) NULL;
ALTER TABLE media_analysis ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_media_analysis_fence ON media_analysis(task_instance_id,fencing_token);
