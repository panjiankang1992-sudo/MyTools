CREATE TABLE audiobook_quota_usage (
    owner_id BIGINT NOT NULL,
    usage_date DATE NOT NULL,
    reserved_character_count BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (owner_id, usage_date)
);

CREATE TABLE audiobook_quota_reservation (
    generation_id CHAR(36) NOT NULL,
    reservation_type VARCHAR(32) NOT NULL,
    owner_id BIGINT NOT NULL,
    usage_date DATE NOT NULL,
    character_count BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (generation_id, reservation_type),
    CONSTRAINT fk_audiobook_quota_reservation_generation
        FOREIGN KEY (generation_id) REFERENCES audiobook_generation(id)
);

CREATE INDEX idx_audiobook_quota_reservation_owner_date
    ON audiobook_quota_reservation (owner_id, usage_date);
