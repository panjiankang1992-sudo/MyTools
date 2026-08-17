CREATE TABLE IF NOT EXISTS media_package (
    id BIGINT NOT NULL PRIMARY KEY,
    package_key VARCHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(255),
    directory_path VARCHAR(1024) NOT NULL,
    primary_file_id BIGINT,
    display_name VARCHAR(255) NOT NULL,
    summary VARCHAR(500),
    description TEXT,
    import_status VARCHAR(32) NOT NULL,
    analysis_status VARCHAR(32) NOT NULL,
    pipeline_version VARCHAR(64),
    manifest_version INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_media_package_key (package_key),
    UNIQUE KEY uq_media_package_path (directory_path(768))
);

CREATE TABLE IF NOT EXISTS media_package_asset (
    id BIGINT NOT NULL PRIMARY KEY,
    package_id BIGINT NOT NULL,
    local_file_id BIGINT,
    asset_role VARCHAR(32) NOT NULL,
    relative_path VARCHAR(512) NOT NULL,
    sequence_no INT,
    timestamp_ms BIGINT,
    content_hash CHAR(64),
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_media_package_asset_path (package_id, relative_path),
    UNIQUE KEY uq_media_package_asset_sequence (package_id, asset_role, sequence_no)
);

CREATE TABLE IF NOT EXISTS media_tag_artifact (
    id BIGINT NOT NULL PRIMARY KEY,
    local_file_id BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    producer VARCHAR(32) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(255) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    input_kind VARCHAR(64) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    generated_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_media_tag_artifact_policy (content_hash, prompt_version, input_fingerprint)
);
