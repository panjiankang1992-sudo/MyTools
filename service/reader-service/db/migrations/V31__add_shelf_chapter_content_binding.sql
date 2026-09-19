-- 为章节归属提供跨表复合外键，历史内容均采用显式删除。
ALTER TABLE shelf_book ADD CONSTRAINT uk_shelf_book_id_owner UNIQUE (id, owner_id);
ALTER TABLE book_source ADD CONSTRAINT uk_book_source_id_owner UNIQUE (id, owner_id);
ALTER TABLE ebook_asset ADD CONSTRAINT uk_ebook_asset_id_owner UNIQUE (id, owner_id);

-- 同一用户的准备任务限额以数据库行锁串行化，不依赖单机计数。
CREATE TABLE reader_chapter_preparation_owner_guard (
    owner_id BIGINT PRIMARY KEY
);

CREATE TABLE shelf_book_content_binding (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    shelf_book_id CHAR(36) NOT NULL,
    binding_type VARCHAR(32) NOT NULL,
    source_id CHAR(36),
    source_version INT,
    source_book_key CHAR(64),
    media_item_id CHAR(36),
    media_asset_id CHAR(36),
    media_content_sha256 CHAR(64),
    ebook_asset_id CHAR(36),
    binding_revision BIGINT NOT NULL,
    bound_shelf_version BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    catalog_revision BIGINT NOT NULL DEFAULT 0,
    catalog_sha256 CHAR(64),
    last_error_code VARCHAR(32),
    next_retry_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_shelf_binding_book UNIQUE (shelf_book_id),
    CONSTRAINT uk_shelf_binding_owner UNIQUE (id, owner_id),
    CONSTRAINT uk_shelf_binding_scope UNIQUE (id, owner_id, shelf_book_id),
    CONSTRAINT fk_shelf_binding_book FOREIGN KEY (shelf_book_id, owner_id)
        REFERENCES shelf_book(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shelf_binding_source_owner FOREIGN KEY (source_id, owner_id)
        REFERENCES book_source(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shelf_binding_source_version FOREIGN KEY (source_id, source_version)
        REFERENCES book_source_version(book_source_id, version) ON DELETE RESTRICT,
    CONSTRAINT fk_shelf_binding_ebook FOREIGN KEY (ebook_asset_id, owner_id)
        REFERENCES ebook_asset(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT ck_shelf_binding_revision CHECK (binding_revision > 0 AND catalog_revision >= 0 AND bound_shelf_version > 0),
    CONSTRAINT ck_shelf_binding_status CHECK (status IN ('PREPARING', 'ACTIVE', 'STALE', 'BROKEN')),
    CONSTRAINT ck_shelf_binding_source CHECK (
        (binding_type = 'SOURCE_RUNTIME' AND source_id IS NOT NULL AND source_version IS NOT NULL
            AND source_version > 0 AND source_book_key IS NOT NULL
            AND media_item_id IS NULL AND media_asset_id IS NULL AND media_content_sha256 IS NULL
            AND ebook_asset_id IS NULL)
        OR (binding_type = 'EBOOK_ASSET' AND source_id IS NULL AND source_version IS NULL
            AND source_book_key IS NULL AND media_item_id IS NOT NULL AND media_asset_id IS NOT NULL
            AND media_content_sha256 IS NOT NULL AND (status <> 'ACTIVE' OR ebook_asset_id IS NOT NULL))),
    CONSTRAINT ck_shelf_binding_active CHECK (
        status <> 'ACTIVE' OR (catalog_revision > 0 AND catalog_sha256 IS NOT NULL))
);

CREATE TABLE shelf_book_preparation_dispatch (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    binding_id CHAR(36) NOT NULL,
    binding_revision BIGINT NOT NULL,
    phase VARCHAR(32) NOT NULL,
    preparation_round INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    deterministic_request_id CHAR(36) NOT NULL,
    invocation_id CHAR(36),
    scheduler_idempotency_key VARBINARY(160),
    task_instance_id CHAR(36),
    execution_id CHAR(36),
    fencing_token BIGINT,
    dispatch_epoch BIGINT NOT NULL DEFAULT 0,
    dispatch_attempt_count INT NOT NULL DEFAULT 0,
    next_dispatch_at TIMESTAMP(6) NOT NULL,
    claim_owner VARCHAR(128),
    claimed_until TIMESTAMP(6),
    catalog_canonicalization_version VARCHAR(32),
    catalog_item_count INT,
    catalog_manifest_sha256 CHAR(64),
    last_error_code VARCHAR(32),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_shelf_prep_phase UNIQUE (binding_id, binding_revision, phase, preparation_round),
    CONSTRAINT uk_shelf_prep_key UNIQUE (scheduler_idempotency_key),
    CONSTRAINT uk_shelf_prep_request UNIQUE (deterministic_request_id),
    CONSTRAINT uk_shelf_prep_scope UNIQUE (id, owner_id, binding_id, binding_revision),
    CONSTRAINT fk_shelf_prep_binding FOREIGN KEY (binding_id, owner_id)
        REFERENCES shelf_book_content_binding(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT ck_shelf_prep_revision CHECK (binding_revision > 0 AND preparation_round > 0 AND dispatch_epoch >= 0 AND dispatch_attempt_count >= 0),
    CONSTRAINT ck_shelf_prep_status CHECK (
        status IN ('PENDING_DISPATCH', 'DISPATCHED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_shelf_prep_phase CHECK (
        (phase = 'SOURCE_CATALOG' AND invocation_id IS NOT NULL AND scheduler_idempotency_key IS NULL
            AND task_instance_id IS NULL AND execution_id IS NULL AND fencing_token IS NULL)
        OR (phase IN ('EBOOK_IMPORT', 'EBOOK_TEXT_PROJECTION') AND invocation_id IS NULL
            AND scheduler_idempotency_key IS NOT NULL)),
    CONSTRAINT ck_shelf_prep_lease CHECK (
        (claim_owner IS NULL AND claimed_until IS NULL) OR (claim_owner IS NOT NULL AND claimed_until IS NOT NULL)),
    CONSTRAINT ck_shelf_prep_manifest CHECK (
        (catalog_item_count IS NULL AND catalog_manifest_sha256 IS NULL AND catalog_canonicalization_version IS NULL)
        OR (catalog_item_count IS NOT NULL AND catalog_item_count BETWEEN 1 AND 50000
            AND catalog_manifest_sha256 IS NOT NULL AND catalog_canonicalization_version IS NOT NULL)),
    CONSTRAINT ck_shelf_prep_sealed CHECK (
        phase <> 'SOURCE_CATALOG' OR status <> 'SUCCEEDED' OR catalog_manifest_sha256 IS NOT NULL)
);
CREATE INDEX idx_shelf_prep_dispatch ON shelf_book_preparation_dispatch(status, next_dispatch_at, claimed_until);

CREATE TABLE shelf_book_catalog_projection_staging (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    dispatch_id CHAR(36) NOT NULL,
    binding_id CHAR(36) NOT NULL,
    binding_revision BIGINT NOT NULL,
    ordinal INT NOT NULL,
    stable_chapter_key_sha256 CHAR(64) NOT NULL,
    title VARCHAR(500) NOT NULL,
    content_kind VARCHAR(32) NOT NULL,
    locator_ciphertext MEDIUMTEXT NOT NULL,
    locator_sha256 CHAR(64) NOT NULL,
    item_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_shelf_catalog_stage_ordinal UNIQUE (dispatch_id, ordinal),
    CONSTRAINT uk_shelf_catalog_stage_key UNIQUE (dispatch_id, stable_chapter_key_sha256),
    CONSTRAINT fk_shelf_catalog_stage_scope FOREIGN KEY (dispatch_id, owner_id, binding_id, binding_revision)
        REFERENCES shelf_book_preparation_dispatch(id, owner_id, binding_id, binding_revision) ON DELETE RESTRICT,
    CONSTRAINT ck_shelf_catalog_stage_index CHECK (ordinal >= 0 AND ordinal < 50000),
    CONSTRAINT ck_shelf_catalog_stage_kind CHECK (content_kind IN ('TEXT', 'PDF', 'IMAGE'))
);

CREATE TABLE shelf_book_source_locator (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    binding_id CHAR(36) NOT NULL,
    binding_revision BIGINT NOT NULL,
    source_version INT NOT NULL,
    locator_scope VARCHAR(16) NOT NULL,
    locator_ciphertext MEDIUMTEXT NOT NULL,
    locator_sha256 CHAR(64) NOT NULL,
    allowed_scheme VARCHAR(16) NOT NULL,
    allowed_host VARCHAR(253) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_shelf_locator_key UNIQUE (binding_id, binding_revision, source_version, locator_scope, locator_sha256),
    CONSTRAINT uk_shelf_locator_scope UNIQUE (id, owner_id, binding_id, binding_revision, source_version),
    CONSTRAINT fk_shelf_locator_binding FOREIGN KEY (binding_id, owner_id)
        REFERENCES shelf_book_content_binding(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT ck_shelf_locator_scope CHECK (locator_scope IN ('BOOK', 'CHAPTER')),
    CONSTRAINT ck_shelf_locator_scheme CHECK (allowed_scheme IN ('http', 'https')),
    CONSTRAINT ck_shelf_locator_version CHECK (source_version > 0 AND binding_revision > 0)
);

CREATE TABLE shelf_book_text_projection (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    shelf_book_id CHAR(36) NOT NULL,
    binding_id CHAR(36) NOT NULL,
    binding_revision BIGINT NOT NULL,
    media_asset_id CHAR(36) NOT NULL,
    media_content_sha256 CHAR(64) NOT NULL,
    ebook_asset_id CHAR(36) NOT NULL,
    projection_format_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    chapter_count INT,
    manifest_sha256 CHAR(64),
    last_error_code VARCHAR(32),
    created_at TIMESTAMP(6) NOT NULL,
    sealed_at TIMESTAMP(6),
    CONSTRAINT uk_shelf_text_projection_revision UNIQUE (binding_id, binding_revision),
    CONSTRAINT uk_shelf_text_projection_scope UNIQUE (id, owner_id, binding_id, binding_revision),
    CONSTRAINT fk_shelf_text_projection_binding FOREIGN KEY (binding_id, owner_id, shelf_book_id)
        REFERENCES shelf_book_content_binding(id, owner_id, shelf_book_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shelf_text_projection_asset FOREIGN KEY (ebook_asset_id, owner_id)
        REFERENCES ebook_asset(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT ck_shelf_text_projection_revision CHECK (binding_revision > 0),
    CONSTRAINT ck_shelf_text_projection_status CHECK (status IN ('BUILDING', 'SEALED', 'FAILED')),
    CONSTRAINT ck_shelf_text_projection_seal CHECK (status <> 'SEALED' OR
        (chapter_count IS NOT NULL AND chapter_count BETWEEN 1 AND 50000
            AND manifest_sha256 IS NOT NULL AND sealed_at IS NOT NULL))
);

CREATE TABLE shelf_book_text_projection_chapter (
    id CHAR(36) PRIMARY KEY,
    projection_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    binding_id CHAR(36) NOT NULL,
    binding_revision BIGINT NOT NULL,
    ordinal INT NOT NULL,
    stable_chapter_key_sha256 CHAR(64) NOT NULL,
    title VARCHAR(500) NOT NULL,
    content_text MEDIUMTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    codepoint_count BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_shelf_text_chapter_ordinal UNIQUE (projection_id, ordinal),
    CONSTRAINT uk_shelf_text_chapter_key UNIQUE (projection_id, stable_chapter_key_sha256),
    CONSTRAINT uk_shelf_text_chapter_scope UNIQUE (projection_id, ordinal, owner_id, binding_id, binding_revision),
    CONSTRAINT fk_shelf_text_chapter_projection FOREIGN KEY (projection_id, owner_id, binding_id, binding_revision)
        REFERENCES shelf_book_text_projection(id, owner_id, binding_id, binding_revision) ON DELETE RESTRICT,
    CONSTRAINT ck_shelf_text_chapter_size CHECK (ordinal >= 0 AND ordinal < 50000 AND codepoint_count > 0)
);

CREATE TABLE shelf_book_chapter (
    id CHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    shelf_book_id CHAR(36) NOT NULL,
    content_binding_id CHAR(36) NOT NULL,
    binding_revision BIGINT NOT NULL,
    catalog_revision BIGINT NOT NULL,
    chapter_index INT NOT NULL,
    chapter_key_sha256 CHAR(64) NOT NULL,
    chapter_title VARCHAR(500) NOT NULL,
    locator_kind VARCHAR(32) NOT NULL,
    source_locator_id CHAR(36),
    source_version INT,
    ebook_entry_index INT,
    text_projection_id CHAR(36),
    content_kind VARCHAR(32) NOT NULL,
    content_sha256 CHAR(64),
    active BOOLEAN NOT NULL,
    adaptation_delete_epoch BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_shelf_chapter_key UNIQUE (owner_id, shelf_book_id, binding_revision, chapter_key_sha256),
    CONSTRAINT uk_shelf_chapter_scope UNIQUE (id, owner_id, shelf_book_id),
    CONSTRAINT fk_shelf_chapter_book FOREIGN KEY (shelf_book_id, owner_id)
        REFERENCES shelf_book(id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shelf_chapter_binding FOREIGN KEY (content_binding_id, owner_id, shelf_book_id)
        REFERENCES shelf_book_content_binding(id, owner_id, shelf_book_id) ON DELETE RESTRICT,
    CONSTRAINT fk_shelf_chapter_locator FOREIGN KEY (source_locator_id, owner_id, content_binding_id, binding_revision, source_version)
        REFERENCES shelf_book_source_locator(id, owner_id, binding_id, binding_revision, source_version) ON DELETE RESTRICT,
    CONSTRAINT fk_shelf_chapter_projection FOREIGN KEY
        (text_projection_id, ebook_entry_index, owner_id, content_binding_id, binding_revision)
        REFERENCES shelf_book_text_projection_chapter(projection_id, ordinal, owner_id, binding_id, binding_revision)
        ON DELETE RESTRICT,
    CONSTRAINT ck_shelf_chapter_revision CHECK (
        binding_revision > 0 AND catalog_revision > 0 AND adaptation_delete_epoch >= 0),
    CONSTRAINT ck_shelf_chapter_index CHECK (chapter_index >= 0 AND chapter_index < 50000),
    CONSTRAINT ck_shelf_chapter_kind CHECK (content_kind IN ('TEXT', 'PDF', 'IMAGE')),
    CONSTRAINT ck_shelf_chapter_locator CHECK (
        (locator_kind = 'SOURCE_CATALOG_KEY' AND source_locator_id IS NOT NULL AND source_version IS NOT NULL
            AND source_version > 0 AND ebook_entry_index IS NULL AND text_projection_id IS NULL)
        OR (locator_kind = 'EBOOK_CATALOG_ENTRY' AND source_locator_id IS NULL AND source_version IS NULL
            AND ebook_entry_index IS NOT NULL AND text_projection_id IS NOT NULL))
);
CREATE INDEX idx_shelf_chapter_catalog ON shelf_book_chapter(owner_id, shelf_book_id, active, chapter_index, id);
