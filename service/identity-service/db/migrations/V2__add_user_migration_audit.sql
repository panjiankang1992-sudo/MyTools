CREATE TABLE identity_user_migration (
 migration_key VARCHAR(128) NOT NULL,
 legacy_user_id BIGINT NOT NULL,
 payload_sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY (migration_key, legacy_user_id),
 CONSTRAINT fk_identity_migration_user FOREIGN KEY (legacy_user_id) REFERENCES identity_user(id)
);
