-- 授权修订防止旧设备迟到同意在撤销后重新授权；事件仅追加，不覆盖历史。
CREATE TABLE reader_adaptation_consent_state (
    owner_id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ck_adaptation_consent_revision CHECK (revision >= 0 AND revision <= 9007199254740991)
);
CREATE TABLE reader_adaptation_consent_event (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    operation_kind VARCHAR(16) NOT NULL,
    disclosure_version VARBINARY(64),
    disclosure_sha256 CHAR(64),
    rights_attestation_version VARBINARY(64),
    audit_source VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_adaptation_consent_event UNIQUE (owner_id, revision),
    CONSTRAINT fk_adaptation_consent_event_owner FOREIGN KEY (owner_id)
        REFERENCES reader_adaptation_consent_state(owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_consent_event_disclosure FOREIGN KEY (disclosure_version, disclosure_sha256, rights_attestation_version)
        REFERENCES reader_adaptation_provider_disclosure(version, disclosure_sha256, rights_attestation_version) ON DELETE RESTRICT,
    CONSTRAINT ck_adaptation_consent_event CHECK (revision > 0 AND audit_source = 'APP_EXPLICIT' AND
        ((operation_kind = 'ACCEPT' AND disclosure_version IS NOT NULL AND disclosure_sha256 IS NOT NULL AND rights_attestation_version IS NOT NULL)
            OR (operation_kind = 'REVOKE_ALL' AND disclosure_version IS NULL AND disclosure_sha256 IS NULL AND rights_attestation_version IS NULL)))
);
ALTER TABLE novel_chapter_adaptation ADD COLUMN consent_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE novel_chapter_adaptation ADD CONSTRAINT ck_adaptation_consent_snapshot CHECK (consent_revision >= 0);
