-- 章节版本号在删除历史后也不复用，业务创建与删除共用章节行锁。
ALTER TABLE shelf_book_chapter ADD COLUMN next_adaptation_revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE shelf_book_chapter ADD CONSTRAINT ck_shelf_chapter_next_adaptation CHECK (next_adaptation_revision > 0);

CREATE TABLE novel_adaptation_provider_deployment (
    id VARBINARY(128) PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL,
    model_id VARBINARY(256) NOT NULL,
    credential_generation BIGINT NOT NULL,
    contract_sha256 CHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_adaptation_deployment_contract UNIQUE (id, provider_code, model_id, credential_generation),
    CONSTRAINT ck_adaptation_deployment_generation CHECK (credential_generation > 0)
);

CREATE TABLE reader_adaptation_provider_disclosure (
    version VARBINARY(64) PRIMARY KEY,
    disclosure_sha256 CHAR(64) NOT NULL,
    rights_attestation_version VARBINARY(64) NOT NULL,
    payload_json JSON NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_adaptation_disclosure_content UNIQUE (version, disclosure_sha256, rights_attestation_version)
);

CREATE TABLE reader_adaptation_provider_consent (
    owner_id BIGINT NOT NULL,
    disclosure_version VARBINARY(64) NOT NULL,
    disclosure_sha256 CHAR(64) NOT NULL,
    rights_attestation_version VARBINARY(64) NOT NULL,
    accepted_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    audit_source VARCHAR(32) NOT NULL,
    PRIMARY KEY (owner_id, disclosure_version),
    CONSTRAINT fk_adaptation_consent_disclosure FOREIGN KEY (disclosure_version, disclosure_sha256, rights_attestation_version)
        REFERENCES reader_adaptation_provider_disclosure(version, disclosure_sha256, rights_attestation_version) ON DELETE RESTRICT
);

CREATE TABLE novel_chapter_adaptation_request_receipt (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    idempotency_key VARBINARY(128) NOT NULL,
    operation_kind VARCHAR(16) NOT NULL,
    shelf_book_id CHAR(36) NOT NULL,
    chapter_id CHAR(36) NOT NULL,
    trigger_adaptation_id CHAR(36),
    request_fingerprint_sha256 CHAR(64) NOT NULL,
    canonicalization_version VARCHAR(64) NOT NULL,
    adaptation_id CHAR(36),
    response_status INT NOT NULL,
    response_snapshot_json JSON NOT NULL,
    tombstoned_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_adaptation_receipt_key UNIQUE (owner_id, idempotency_key),
    CONSTRAINT uk_adaptation_receipt_owner UNIQUE (id, owner_id),
    CONSTRAINT ck_adaptation_receipt_kind CHECK (operation_kind IN ('INITIAL', 'OPTIMIZE', 'REGENERATE')),
    CONSTRAINT ck_adaptation_receipt_target CHECK ((operation_kind = 'INITIAL' AND trigger_adaptation_id IS NULL)
        OR (operation_kind <> 'INITIAL' AND trigger_adaptation_id IS NOT NULL)),
    CONSTRAINT ck_adaptation_receipt_status CHECK (response_status IN (202, 410)),
    CONSTRAINT ck_adaptation_receipt_tombstone CHECK (
        (response_status = 202 AND adaptation_id IS NOT NULL AND tombstoned_at IS NULL)
        OR (response_status = 410 AND tombstoned_at IS NOT NULL))
);

CREATE TABLE novel_chapter_adaptation (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    shelf_book_id CHAR(36) NOT NULL,
    chapter_id CHAR(36) NOT NULL,
    content_binding_id CHAR(36) NOT NULL,
    chapter_title_snapshot VARCHAR(500) NOT NULL,
    request_kind VARCHAR(16) NOT NULL,
    request_receipt_id CHAR(36) NOT NULL,
    request_fingerprint_sha256 CHAR(64) NOT NULL,
    request_canonicalization_version VARCHAR(64) NOT NULL,
    intent_text TEXT NOT NULL,
    normalized_intent_text TEXT NOT NULL,
    intent_sha256 CHAR(64) NOT NULL,
    expected_binding_revision BIGINT NOT NULL,
    expected_catalog_revision BIGINT NOT NULL,
    expected_source_sha256 CHAR(64),
    disclosure_version VARBINARY(64) NOT NULL,
    revision_number BIGINT NOT NULL,
    chapter_delete_epoch BIGINT NOT NULL,
    base_kind VARCHAR(16) NOT NULL,
    original_content_sha256 CHAR(64),
    base_content_sha256 CHAR(64),
    context_status VARCHAR(16) NOT NULL,
    context_role_count INT,
    context_manifest_version VARCHAR(64),
    context_manifest_sha256 CHAR(64),
    context_identity_json JSON,
    context_sealed_at TIMESTAMP(6),
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(32) NOT NULL,
    task_instance_id CHAR(36),
    current_execution_id CHAR(36),
    fencing_token BIGINT,
    deadline_at TIMESTAMP(6) NOT NULL,
    cancel_requested_at TIMESTAMP(6),
    provider_call_budget_remaining INT NOT NULL DEFAULT 5,
    provider_retry_budget_remaining INT NOT NULL DEFAULT 2,
    provider_millis_remaining BIGINT NOT NULL DEFAULT 660000,
    dispatch_epoch BIGINT NOT NULL DEFAULT 0,
    dispatch_attempt_count INT NOT NULL DEFAULT 0,
    next_dispatch_at TIMESTAMP(6) NOT NULL,
    dispatch_claim_owner VARCHAR(128),
    dispatch_claimed_until TIMESTAMP(6),
    recovery_claim_owner VARCHAR(128),
    recovery_claimed_until TIMESTAMP(6),
    next_recovery_at TIMESTAMP(6),
    provider_deployment_id VARBINARY(128) NOT NULL,
    provider_code VARCHAR(32) NOT NULL,
    model_id VARBINARY(256) NOT NULL,
    credential_generation BIGINT NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    constraint_version VARCHAR(64) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    repair_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(32),
    last_error_trace_id CHAR(36),
    deletion_state VARCHAR(16) NOT NULL DEFAULT 'LIVE',
    tombstoned_at TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_adaptation_owner UNIQUE (id, owner_id),
    CONSTRAINT uk_adaptation_scope UNIQUE (id, owner_id, shelf_book_id, chapter_id),
    CONSTRAINT uk_adaptation_receipt UNIQUE (request_receipt_id),
    CONSTRAINT uk_adaptation_revision UNIQUE (owner_id, chapter_id, revision_number),
    CONSTRAINT fk_adaptation_chapter FOREIGN KEY (chapter_id, owner_id, shelf_book_id)
        REFERENCES shelf_book_chapter(id, owner_id, shelf_book_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_binding FOREIGN KEY (content_binding_id, owner_id, shelf_book_id)
        REFERENCES shelf_book_content_binding(id, owner_id, shelf_book_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_receipt FOREIGN KEY (request_receipt_id, owner_id)
        REFERENCES novel_chapter_adaptation_request_receipt(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_consent FOREIGN KEY (owner_id, disclosure_version)
        REFERENCES reader_adaptation_provider_consent(owner_id, disclosure_version) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_provider FOREIGN KEY (provider_deployment_id, provider_code, model_id, credential_generation)
        REFERENCES novel_adaptation_provider_deployment(id, provider_code, model_id, credential_generation) ON DELETE RESTRICT,
    CONSTRAINT ck_adaptation_kind CHECK ((request_kind IN ('INITIAL', 'REGENERATE') AND base_kind = 'ORIGINAL')
        OR (request_kind = 'OPTIMIZE' AND base_kind = 'PARENT_RESULT')),
    CONSTRAINT ck_adaptation_status CHECK (status IN ('PENDING_DISPATCH', 'QUEUED', 'CONTEXT_FREEZING', 'ANALYZING',
        'GENERATING', 'VALIDATING', 'REPAIRING', 'PERSISTING', 'COMPLETED', 'CANCEL_REQUESTED', 'CANCELLED', 'FAILED')),
    CONSTRAINT ck_adaptation_revision CHECK (expected_binding_revision > 0 AND expected_catalog_revision > 0
        AND revision_number > 0 AND chapter_delete_epoch >= 0 AND version > 0 AND dispatch_epoch >= 0 AND dispatch_attempt_count >= 0),
    CONSTRAINT ck_adaptation_budgets CHECK (provider_call_budget_remaining BETWEEN 0 AND 5
        AND provider_retry_budget_remaining BETWEEN 0 AND 2 AND provider_millis_remaining BETWEEN 0 AND 660000
        AND attempt_count BETWEEN 0 AND 5 AND repair_count BETWEEN 0 AND 1),
    CONSTRAINT ck_adaptation_context CHECK (
        (context_status = 'BUILDING' AND context_sealed_at IS NULL AND context_manifest_sha256 IS NULL
            AND context_role_count IS NULL AND context_identity_json IS NULL AND original_content_sha256 IS NULL AND base_content_sha256 IS NULL)
        OR (context_status = 'SEALED' AND context_sealed_at IS NOT NULL AND context_manifest_sha256 IS NOT NULL
            AND context_manifest_version IS NOT NULL AND context_identity_json IS NOT NULL
            AND original_content_sha256 IS NOT NULL AND base_content_sha256 IS NOT NULL
            AND ((request_kind = 'OPTIMIZE' AND context_role_count = 5) OR (request_kind <> 'OPTIMIZE' AND context_role_count = 4)))),
    CONSTRAINT ck_adaptation_terminal CHECK ((status IN ('COMPLETED', 'CANCELLED', 'FAILED') AND finished_at IS NOT NULL)
        OR (status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED') AND finished_at IS NULL)),
    CONSTRAINT ck_adaptation_completed CHECK (status <> 'COMPLETED' OR context_status = 'SEALED'),
    CONSTRAINT ck_adaptation_deletion CHECK ((deletion_state = 'LIVE' AND tombstoned_at IS NULL)
        OR (deletion_state = 'TOMBSTONED' AND tombstoned_at IS NOT NULL)),
    CONSTRAINT ck_adaptation_dispatch_lease CHECK ((dispatch_claim_owner IS NULL AND dispatch_claimed_until IS NULL)
        OR (dispatch_claim_owner IS NOT NULL AND dispatch_claimed_until IS NOT NULL)),
    CONSTRAINT ck_adaptation_recovery_lease CHECK ((recovery_claim_owner IS NULL AND recovery_claimed_until IS NULL)
        OR (recovery_claim_owner IS NOT NULL AND recovery_claimed_until IS NOT NULL))
);
CREATE INDEX idx_adaptation_history ON novel_chapter_adaptation(owner_id, chapter_id, deletion_state, revision_number);
CREATE INDEX idx_adaptation_dispatch ON novel_chapter_adaptation(status, next_dispatch_at, dispatch_claimed_until);

CREATE TABLE novel_chapter_adaptation_context (
    id CHAR(36) PRIMARY KEY,
    adaptation_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    shelf_book_id CHAR(36) NOT NULL,
    chapter_id CHAR(36) NOT NULL,
    context_role VARCHAR(32) NOT NULL,
    sequence_no INT NOT NULL,
    source_chapter_id CHAR(36),
    content_text MEDIUMTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    codepoint_count BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_adaptation_context_role UNIQUE (adaptation_id, context_role, sequence_no),
    CONSTRAINT fk_adaptation_context_scope FOREIGN KEY (adaptation_id, owner_id, shelf_book_id, chapter_id)
        REFERENCES novel_chapter_adaptation(id, owner_id, shelf_book_id, chapter_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_context_source FOREIGN KEY (source_chapter_id, owner_id, shelf_book_id)
        REFERENCES shelf_book_chapter(id, owner_id, shelf_book_id) ON DELETE RESTRICT,
    CONSTRAINT ck_adaptation_context_role CHECK (
        (context_role IN ('TARGET_ORIGINAL', 'BASE_INPUT', 'PREVIOUS_TAIL', 'NEXT_HEAD') AND source_chapter_id IS NOT NULL)
        OR (context_role IN ('BOOK_START_MARKER', 'BOOK_END_MARKER', 'CATALOG_METADATA') AND source_chapter_id IS NULL)),
    CONSTRAINT ck_adaptation_context_size CHECK (sequence_no = 0 AND codepoint_count > 0 AND codepoint_count <= 120000)
);

CREATE TABLE novel_chapter_adaptation_lineage (
    child_adaptation_id CHAR(36) PRIMARY KEY,
    root_adaptation_id CHAR(36) NOT NULL,
    parent_adaptation_id CHAR(36),
    trigger_adaptation_id CHAR(36),
    owner_id BIGINT NOT NULL,
    shelf_book_id CHAR(36) NOT NULL,
    chapter_id CHAR(36) NOT NULL,
    CONSTRAINT fk_adaptation_lineage_child FOREIGN KEY (child_adaptation_id, owner_id, shelf_book_id, chapter_id)
        REFERENCES novel_chapter_adaptation(id, owner_id, shelf_book_id, chapter_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_lineage_root FOREIGN KEY (root_adaptation_id, owner_id, shelf_book_id, chapter_id)
        REFERENCES novel_chapter_adaptation(id, owner_id, shelf_book_id, chapter_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_lineage_parent FOREIGN KEY (parent_adaptation_id, owner_id, shelf_book_id, chapter_id)
        REFERENCES novel_chapter_adaptation(id, owner_id, shelf_book_id, chapter_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_lineage_trigger FOREIGN KEY (trigger_adaptation_id, owner_id, shelf_book_id, chapter_id)
        REFERENCES novel_chapter_adaptation(id, owner_id, shelf_book_id, chapter_id) ON DELETE RESTRICT,
    CONSTRAINT ck_adaptation_lineage_shape CHECK (
        (child_adaptation_id = root_adaptation_id AND parent_adaptation_id IS NULL AND trigger_adaptation_id IS NULL)
        OR (child_adaptation_id <> root_adaptation_id AND trigger_adaptation_id IS NOT NULL
            AND trigger_adaptation_id <> child_adaptation_id
            AND (parent_adaptation_id IS NULL OR parent_adaptation_id = trigger_adaptation_id)))
);

CREATE TABLE novel_chapter_adaptation_attempt (
    id CHAR(36) PRIMARY KEY,
    adaptation_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    call_kind VARCHAR(16) NOT NULL,
    task_instance_id CHAR(36) NOT NULL,
    execution_id CHAR(36) NOT NULL,
    fencing_token BIGINT NOT NULL,
    scheduler_attempt_no INT,
    provider_deployment_id VARBINARY(128) NOT NULL,
    model_id VARBINARY(256) NOT NULL,
    credential_generation BIGINT NOT NULL,
    provider_attempt_id CHAR(36) NOT NULL,
    provider_request_id VARCHAR(256),
    request_sha256 CHAR(64) NOT NULL,
    finish_reason VARCHAR(32),
    transmission_count INT NOT NULL DEFAULT 0,
    last_send_started_at TIMESTAMP(6),
    plan_json JSON,
    plan_sha256 CHAR(64),
    output_text MEDIUMTEXT,
    output_sha256 CHAR(64),
    output_codepoint_count BIGINT,
    status VARCHAR(32) NOT NULL,
    viewable_candidate BOOLEAN NOT NULL DEFAULT FALSE,
    rejection_category VARCHAR(32),
    repair_of_attempt_id CHAR(36),
    archived_only BOOLEAN NOT NULL DEFAULT FALSE,
    settlement_token_sha256 CHAR(64),
    settlement_expires_at TIMESTAMP(6),
    chapter_delete_epoch BIGINT NOT NULL,
    terminal_payload_sha256 CHAR(64),
    input_tokens BIGINT,
    output_tokens BIGINT,
    http_status INT,
    error_code VARCHAR(32),
    diagnostic_sha256 CHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT uk_adaptation_attempt_no UNIQUE (adaptation_id, attempt_no),
    CONSTRAINT uk_adaptation_provider_attempt UNIQUE (provider_attempt_id),
    CONSTRAINT uk_adaptation_attempt_scope UNIQUE (id, adaptation_id, owner_id),
    CONSTRAINT fk_adaptation_attempt_parent FOREIGN KEY (adaptation_id, owner_id)
        REFERENCES novel_chapter_adaptation(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_attempt_repair FOREIGN KEY (repair_of_attempt_id, adaptation_id, owner_id)
        REFERENCES novel_chapter_adaptation_attempt(id, adaptation_id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT ck_adaptation_attempt_kind CHECK (call_kind IN ('PLAN', 'GENERATE', 'CRITIC', 'REPAIR')),
    CONSTRAINT ck_adaptation_attempt_status CHECK (status IN ('REGISTERED', 'SEND_STARTED', 'SUCCEEDED', 'FAILED', 'CALL_OUTCOME_UNKNOWN')),
    CONSTRAINT ck_adaptation_attempt_counts CHECK (attempt_no BETWEEN 1 AND 5 AND transmission_count BETWEEN 0 AND 3
        AND fencing_token > 0 AND (scheduler_attempt_no IS NULL OR scheduler_attempt_no > 0) AND credential_generation > 0 AND chapter_delete_epoch >= 0),
    CONSTRAINT ck_adaptation_attempt_settlement_pair CHECK (
        ((settlement_token_sha256 IS NULL AND settlement_expires_at IS NULL)
            OR (settlement_token_sha256 IS NOT NULL AND settlement_expires_at IS NOT NULL))
        AND (status <> 'SEND_STARTED' OR (settlement_token_sha256 IS NOT NULL AND settlement_expires_at IS NOT NULL))),
    CONSTRAINT ck_adaptation_attempt_visibility CHECK (viewable_candidate = FALSE OR
        (call_kind IN ('GENERATE', 'REPAIR') AND status = 'SUCCEEDED' AND output_text IS NOT NULL
            AND output_sha256 IS NOT NULL AND output_codepoint_count IS NOT NULL AND output_codepoint_count BETWEEN 1 AND 120000
            AND (rejection_category IS NULL OR rejection_category = 'CONSTRAINTS'))),
    CONSTRAINT ck_adaptation_attempt_terminal CHECK (
        (status IN ('REGISTERED', 'SEND_STARTED') AND completed_at IS NULL AND terminal_payload_sha256 IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'CALL_OUTCOME_UNKNOWN') AND completed_at IS NOT NULL AND terminal_payload_sha256 IS NOT NULL))
);

CREATE TABLE novel_chapter_adaptation_constraint_set (
    adaptation_id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    plan_attempt_id CHAR(36) NOT NULL,
    deterministic_json JSON NOT NULL,
    supplement_json JSON NOT NULL,
    merged_json JSON NOT NULL,
    deterministic_sha256 CHAR(64) NOT NULL,
    supplement_sha256 CHAR(64) NOT NULL,
    merged_sha256 CHAR(64) NOT NULL,
    constraint_version VARCHAR(64) NOT NULL,
    created_by_execution_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_adaptation_constraint_owner FOREIGN KEY (adaptation_id, owner_id)
        REFERENCES novel_chapter_adaptation(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_constraint_plan FOREIGN KEY (plan_attempt_id, adaptation_id, owner_id)
        REFERENCES novel_chapter_adaptation_attempt(id, adaptation_id, owner_id) ON DELETE RESTRICT
);

CREATE TABLE novel_chapter_adaptation_validation (
    id CHAR(36) PRIMARY KEY,
    adaptation_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    candidate_attempt_id CHAR(36) NOT NULL,
    critic_attempt_id CHAR(36) NOT NULL,
    validation_round INT NOT NULL,
    deterministic_version VARCHAR(64) NOT NULL,
    deterministic_json JSON NOT NULL,
    critic_version VARCHAR(64) NOT NULL,
    critic_json JSON NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    report_json JSON NOT NULL,
    report_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_adaptation_validation_round UNIQUE (adaptation_id, candidate_attempt_id, validation_round),
    CONSTRAINT uk_adaptation_validation_scope UNIQUE (id, adaptation_id, owner_id, candidate_attempt_id),
    CONSTRAINT fk_adaptation_validation_candidate FOREIGN KEY (candidate_attempt_id, adaptation_id, owner_id)
        REFERENCES novel_chapter_adaptation_attempt(id, adaptation_id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_validation_critic FOREIGN KEY (critic_attempt_id, adaptation_id, owner_id)
        REFERENCES novel_chapter_adaptation_attempt(id, adaptation_id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT ck_adaptation_validation_round CHECK (validation_round BETWEEN 1 AND 2),
    CONSTRAINT ck_adaptation_validation_outcome CHECK (outcome IN ('PASS', 'REPAIRABLE', 'BLOCKED'))
);

CREATE TABLE novel_chapter_adaptation_selection (
    adaptation_id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    candidate_attempt_id CHAR(36) NOT NULL,
    validation_id CHAR(36) NOT NULL,
    selected_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_adaptation_selection_parent FOREIGN KEY (adaptation_id, owner_id)
        REFERENCES novel_chapter_adaptation(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_selection_candidate FOREIGN KEY (candidate_attempt_id, adaptation_id, owner_id)
        REFERENCES novel_chapter_adaptation_attempt(id, adaptation_id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_adaptation_selection_validation FOREIGN KEY (validation_id, adaptation_id, owner_id, candidate_attempt_id)
        REFERENCES novel_chapter_adaptation_validation(id, adaptation_id, owner_id, candidate_attempt_id) ON DELETE RESTRICT
);

CREATE TABLE novel_chapter_adaptation_deletion (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    shelf_book_id CHAR(36) NOT NULL,
    chapter_id CHAR(36) NOT NULL,
    target_delete_epoch BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    claim_owner VARCHAR(128),
    claimed_until TIMESTAMP(6),
    last_error_code VARCHAR(32),
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT uk_adaptation_deletion_epoch UNIQUE (owner_id, chapter_id, target_delete_epoch),
    CONSTRAINT fk_adaptation_deletion_chapter FOREIGN KEY (chapter_id, owner_id, shelf_book_id)
        REFERENCES shelf_book_chapter(id, owner_id, shelf_book_id) ON DELETE RESTRICT,
    CONSTRAINT ck_adaptation_deletion_counts CHECK (target_delete_epoch >= 0 AND attempt_count >= 0),
    CONSTRAINT ck_adaptation_deletion_status CHECK (status IN ('DELETING', 'COMPLETED', 'FAILED'))
);
