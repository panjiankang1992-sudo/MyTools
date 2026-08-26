CREATE TABLE download_progress (
    download_request_id CHAR(36) NOT NULL,
    external_item_id VARCHAR(255) NOT NULL,
    downloaded_bytes BIGINT NOT NULL,
    total_bytes BIGINT NOT NULL,
    progress_percent INT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (download_request_id, external_item_id),
    CONSTRAINT fk_download_progress_request FOREIGN KEY (download_request_id) REFERENCES download_request(id),
    CONSTRAINT ck_download_progress_bytes CHECK (downloaded_bytes >= 0 AND total_bytes > 0
        AND downloaded_bytes <= total_bytes),
    CONSTRAINT ck_download_progress_percent CHECK (progress_percent BETWEEN 0 AND 100)
);
