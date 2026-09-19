-- 视频任务发布开关只通过受认证管理 API 更新，保留每次操作的审计标识。
CREATE TABLE video_deployment_audit (
    id VARCHAR(140) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    service_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
