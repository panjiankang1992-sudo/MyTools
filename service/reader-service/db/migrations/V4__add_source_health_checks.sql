ALTER TABLE book_source ADD COLUMN health_status VARCHAR(32);
ALTER TABLE book_source ADD COLUMN health_latency_millis BIGINT;
ALTER TABLE book_source ADD COLUMN health_error_code VARCHAR(128);
ALTER TABLE book_source ADD COLUMN health_checked_at TIMESTAMP(6);

CREATE TABLE book_source_health_check (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    keyword VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    parameters_json JSON NOT NULL,
    checked_count INT NOT NULL DEFAULT 0,
    healthy_count INT NOT NULL DEFAULT 0,
    unhealthy_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_source_health_idempotency (owner_id, idempotency_key)
);

CREATE TABLE book_source_health_result (
    health_check_id CHAR(36) NOT NULL,
    source_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    latency_millis BIGINT NOT NULL,
    error_code VARCHAR(128),
    checked_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (health_check_id, source_id),
    CONSTRAINT fk_source_health_check FOREIGN KEY (health_check_id) REFERENCES book_source_health_check(id),
    CONSTRAINT fk_source_health_source FOREIGN KEY (source_id) REFERENCES book_source(id)
);
