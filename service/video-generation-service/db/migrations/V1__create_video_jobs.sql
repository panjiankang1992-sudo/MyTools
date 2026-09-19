-- 视频生成：上传素材、任务快照、输入、输出与阶段事件。
-- 任务快照保存提交时的模型/工作流修订与实际参数，历史再创作不依赖可变配置。
CREATE TABLE video_upload (
 id CHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, kind VARCHAR(16) NOT NULL,
 mime_type VARCHAR(64) NOT NULL, size_bytes BIGINT NOT NULL, content_sha256 CHAR(64) NOT NULL,
 duration_ms BIGINT, width INT, height INT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_video_upload_owner ON video_upload(owner_id,created_at);

CREATE TABLE video_job (
 id CHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
 request_sha256 CHAR(64) NOT NULL, request_json TEXT NOT NULL,
 original_prompt TEXT NOT NULL, effective_prompt TEXT NOT NULL,
 mode VARCHAR(32) NOT NULL, size VARCHAR(24) NOT NULL,
 width INT NOT NULL, height INT NOT NULL, frames INT NOT NULL, fps INT NOT NULL,
 seed BIGINT NOT NULL, audio_policy VARCHAR(16) NOT NULL,
 model_revision VARCHAR(64) NOT NULL, workflow_revision VARCHAR(64) NOT NULL,
 status VARCHAR(32) NOT NULL, task_id CHAR(36), dispatch_started BOOLEAN NOT NULL DEFAULT FALSE,
 parent_job_id CHAR(36), result_json TEXT, error_code VARCHAR(64), resource_peak_json TEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_video_owner_request UNIQUE(owner_id,idempotency_key),
 CONSTRAINT fk_video_job_parent FOREIGN KEY (parent_job_id) REFERENCES video_job(id) ON DELETE SET NULL
);
CREATE INDEX idx_video_job_status ON video_job(status,created_at);
CREATE INDEX idx_video_job_owner ON video_job(owner_id,created_at);

CREATE TABLE video_input (
 id CHAR(36) PRIMARY KEY, job_id CHAR(36) NOT NULL, upload_id CHAR(36) NOT NULL,
 role VARCHAR(24) NOT NULL, description VARCHAR(200), ordinal INT NOT NULL,
 content_sha256 CHAR(64) NOT NULL, trim_start_ms BIGINT, trim_end_ms BIGINT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_video_input_job FOREIGN KEY (job_id) REFERENCES video_job(id) ON DELETE CASCADE
);
CREATE INDEX idx_video_input_job ON video_input(job_id,ordinal);

CREATE TABLE video_output (
 id CHAR(36) PRIMARY KEY, job_id CHAR(36) NOT NULL, kind VARCHAR(16) NOT NULL,
 asset_id CHAR(36), storage_uri VARCHAR(1024), content_sha256 CHAR(64),
 size_bytes BIGINT, mime_type VARCHAR(64), width INT, height INT, frames INT, fps INT,
 duration_ms BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_video_output UNIQUE(job_id,kind),
 CONSTRAINT fk_video_output_job FOREIGN KEY (job_id) REFERENCES video_job(id) ON DELETE CASCADE
);

CREATE TABLE video_job_event (
 id CHAR(36) PRIMARY KEY, job_id CHAR(36) NOT NULL, stage VARCHAR(32) NOT NULL,
 detail VARCHAR(400), occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_video_job_event_job FOREIGN KEY (job_id) REFERENCES video_job(id) ON DELETE CASCADE
);
CREATE INDEX idx_video_job_event ON video_job_event(job_id,occurred_at);
