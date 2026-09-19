CREATE TABLE novel_chapter_adaptation_attempt_reservation (
    attempt_id CHAR(36) PRIMARY KEY,
    adaptation_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    executor_thumbprint VARCHAR(43) NOT NULL,
    reservation_sha256 CHAR(64) NOT NULL,
    reserved_millis BIGINT NOT NULL,
    reservation_expires_at TIMESTAMP(6) NOT NULL,
    send_by TIMESTAMP(6),
    call_deadline_at TIMESTAMP(6),
    settlement_key_id VARCHAR(64),
    settlement_nonce VARCHAR(43),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_adaptation_attempt_reservation_scope FOREIGN KEY (attempt_id, adaptation_id, owner_id)
        REFERENCES novel_chapter_adaptation_attempt(id, adaptation_id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT ck_adaptation_attempt_reservation_budget CHECK (reserved_millis BETWEEN 1 AND 180000),
    CONSTRAINT ck_adaptation_attempt_reservation_send CHECK (
        (send_by IS NULL AND call_deadline_at IS NULL AND settlement_key_id IS NULL AND settlement_nonce IS NULL)
        OR (send_by IS NOT NULL AND call_deadline_at IS NOT NULL AND settlement_key_id IS NOT NULL AND settlement_nonce IS NOT NULL
            AND send_by <= call_deadline_at))
);

CREATE INDEX idx_adaptation_attempt_reservation_expiry
    ON novel_chapter_adaptation_attempt_reservation(reservation_expires_at, attempt_id);

CREATE TABLE novel_adaptation_provider_circuit (
    bucket_id CHAR(64) PRIMARY KEY,
    bucket_kind VARCHAR(16) NOT NULL,
    provider_deployment_id VARBINARY(128) NOT NULL,
    scope_key VARBINARY(256) NOT NULL,
    state VARCHAR(16) NOT NULL,
    failure_count INT NOT NULL DEFAULT 0,
    window_started_at TIMESTAMP(6),
    opened_at TIMESTAMP(6),
    next_probe_at TIMESTAMP(6),
    probe_epoch BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uk_adaptation_circuit_scope UNIQUE (bucket_kind, provider_deployment_id, scope_key),
    CONSTRAINT fk_adaptation_circuit_deployment FOREIGN KEY (provider_deployment_id)
        REFERENCES novel_adaptation_provider_deployment(id) ON DELETE RESTRICT,
    CONSTRAINT ck_adaptation_circuit_kind CHECK (bucket_kind IN ('AUTH', 'AVAILABILITY')),
    CONSTRAINT ck_adaptation_circuit_state CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    CONSTRAINT ck_adaptation_circuit_counters CHECK (failure_count >= 0 AND probe_epoch >= 0 AND version > 0)
);
