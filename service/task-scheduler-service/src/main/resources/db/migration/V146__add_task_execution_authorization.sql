-- 授权元数据与执行租约同事务变更，只保存随机令牌标识摘要，不保存签名令牌。
ALTER TABLE task_execution ADD CONSTRAINT uk_execution_authorization_scope UNIQUE (id, task_instance_id, fencing_token);

CREATE TABLE task_execution_authorization (
    execution_id CHAR(36) PRIMARY KEY,
    task_instance_id CHAR(36) NOT NULL,
    fencing_token BIGINT NOT NULL,
    audience VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    package_name VARCHAR(128) NOT NULL,
    package_version VARCHAR(64) NOT NULL,
    task_parameters_sha256 CHAR(64) NOT NULL,
    workload_identity VARBINARY(512) NOT NULL,
    node_instance_id VARCHAR(128) NOT NULL,
    cnf_thumbprint VARBINARY(43) NOT NULL,
    assertion_generation BIGINT NOT NULL,
    current_jti_sha256 CHAR(64) NOT NULL UNIQUE,
    previous_jti_sha256 CHAR(64),
    previous_generation BIGINT,
    previous_valid_until TIMESTAMP(6),
    previous_assertion_expires_at TIMESTAMP(6),
    lease_expires_at TIMESTAMP(6) NOT NULL,
    assertion_expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_execution_authorization_execution FOREIGN KEY (execution_id, task_instance_id, fencing_token)
        REFERENCES task_execution(id, task_instance_id, fencing_token) ON DELETE RESTRICT,
    CONSTRAINT fk_execution_authorization_task FOREIGN KEY (task_instance_id) REFERENCES task_instance(id) ON DELETE RESTRICT,
    CONSTRAINT ck_execution_authorization_positive CHECK (fencing_token > 0 AND assertion_generation > 0),
    CONSTRAINT ck_execution_authorization_previous CHECK (
        (previous_jti_sha256 IS NULL AND previous_generation IS NULL AND previous_valid_until IS NULL AND previous_assertion_expires_at IS NULL)
        OR (previous_jti_sha256 IS NOT NULL AND previous_generation > 0 AND previous_generation < assertion_generation
            AND previous_valid_until IS NOT NULL AND previous_assertion_expires_at IS NOT NULL)),
    CONSTRAINT ck_execution_authorization_expiry CHECK (assertion_expires_at <= lease_expires_at),
    CONSTRAINT ck_execution_authorization_resource CHECK (
        (resource_type = 'CHAPTER_ADAPTATION' AND audience = 'reader-adaptation-internal' AND package_name = 'reader_adapt_novel_chapter')
        OR (resource_type = 'EBOOK_BINDING' AND audience = 'reader-ebook-projection-internal' AND package_name = 'reader_project_ebook_text')
        OR (resource_type = 'PROVIDER_PROBE' AND audience = 'reader-provider-probe-internal' AND package_name = 'reader_probe_novel_adaptation_provider'))
);

CREATE INDEX idx_execution_authorization_task ON task_execution_authorization(task_instance_id, revoked_at);
