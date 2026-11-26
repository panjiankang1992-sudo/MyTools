-- 下载终态对账和媒体后处理独立持久化，不占用网络下载任务的并发槽位。
CREATE TABLE download_background_job (
    id VARCHAR(96) PRIMARY KEY,
    kind VARCHAR(32) NOT NULL,
    download_request_id CHAR(36) NOT NULL,
    external_item_id VARCHAR(255),
    payload_json JSON NOT NULL,
    task_instance_id CHAR(36),
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    claim_token CHAR(36),
    claim_until TIMESTAMP(6),
    last_error_code VARCHAR(96),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_download_background_request FOREIGN KEY (download_request_id) REFERENCES download_request(id),
    INDEX idx_download_background_item (download_request_id, external_item_id, kind),
    INDEX idx_download_background_due (kind, state, next_attempt_at, claim_until)
);

-- 发布时接管既有活跃请求的终态对账，固定主键使迁移与恢复可追踪。
INSERT INTO download_background_job
    (id,kind,download_request_id,payload_json,state,next_attempt_at,created_at,updated_at)
SELECT CONCAT('request:',id),'RECONCILE',id,JSON_OBJECT(),'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM download_request WHERE status IN ('ACCEPTED','PLANNING','RUNNING','CANCELLING');
