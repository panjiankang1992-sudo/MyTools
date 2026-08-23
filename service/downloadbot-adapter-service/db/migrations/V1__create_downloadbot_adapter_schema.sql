CREATE TABLE adapter_event (
    id CHAR(36) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_key VARCHAR(255) NOT NULL,
    request_kind VARCHAR(64) NOT NULL,
    parameters_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    download_request_id CHAR(36) NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_adapter_event_event_id (event_id),
    KEY idx_adapter_event_status_updated (status, updated_at),
    KEY idx_adapter_event_download_request (download_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
