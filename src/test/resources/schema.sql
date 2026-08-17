-- H2 Schema for Integration Tests
-- Mode: MySQL compatible

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(20) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(100) UNIQUE,
    phone VARCHAR(20),
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    register_time TIMESTAMP NOT NULL,
    last_login_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);

-- 角色表
CREATE TABLE IF NOT EXISTS t_role (
    id BIGINT PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    create_time TIMESTAMP NOT NULL
);

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS t_user_role (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (user_id, role_id)
);

-- Token表
CREATE TABLE IF NOT EXISTS t_token (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(512) NOT NULL UNIQUE,
    device_info VARCHAR(255),
    expires_at TIMESTAMP NOT NULL,
    create_time TIMESTAMP NOT NULL
);

-- 邮箱验证码表
CREATE TABLE IF NOT EXISTS t_email_verification_code (
    id BIGINT PRIMARY KEY,
    purpose VARCHAR(32) NOT NULL,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    code_hash VARCHAR(128) NOT NULL,
    expire_time TIMESTAMP NOT NULL,
    used_time TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);

-- 错误码表
CREATE TABLE IF NOT EXISTS t_reading_progress (
    user_id BIGINT NOT NULL,
    book_id VARCHAR(200) NOT NULL,
    chapter_title VARCHAR(500) NOT NULL,
    locator BIGINT NOT NULL,
    percentage INT NOT NULL,
    client_updated_at BIGINT NOT NULL,
    server_updated_at BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, book_id)
);

CREATE TABLE IF NOT EXISTS t_reader_marker (
    user_id BIGINT NOT NULL,
    marker_id VARCHAR(100) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    book_id VARCHAR(200) NOT NULL,
    chapter_title VARCHAR(500) NOT NULL,
    locator BIGINT NOT NULL,
    note VARCHAR(2000) NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    client_updated_at BIGINT NOT NULL,
    server_updated_at BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, marker_id)
);

CREATE TABLE IF NOT EXISTS t_shelf_book (
    user_id BIGINT NOT NULL,
    sync_key VARCHAR(80) NOT NULL,
    book_id VARCHAR(1000) NOT NULL,
    name VARCHAR(300) NOT NULL,
    author VARCHAR(200) NOT NULL,
    origin VARCHAR(20) NOT NULL,
    format VARCHAR(20) NOT NULL,
    resource_uri VARCHAR(4096) NOT NULL,
    source_id VARCHAR(4096) NOT NULL,
    remote_cover_url VARCHAR(4096) NOT NULL DEFAULT '',
    client_updated_at BIGINT NOT NULL,
    server_updated_at BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, sync_key)
);

CREATE TABLE IF NOT EXISTS t_synced_book_source (
    user_id BIGINT NOT NULL,
    sync_key VARCHAR(80) NOT NULL,
    source_url VARCHAR(4096) NOT NULL,
    snapshot_json CLOB NOT NULL,
    client_updated_at BIGINT NOT NULL,
    server_updated_at BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, sync_key)
);

-- 错误码表
CREATE TABLE IF NOT EXISTS drive_account (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    remote_key VARCHAR(64) NOT NULL,
    read_only BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(32) NOT NULL,
    last_checked_at TIMESTAMP,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    UNIQUE (user_id, remote_key)
);

CREATE TABLE IF NOT EXISTS drive_item_index (
    id BIGINT PRIMARY KEY,
    drive_id BIGINT NOT NULL,
    remote_path VARCHAR(2048) NOT NULL,
    parent_path VARCHAR(2048) NOT NULL,
    display_name VARCHAR(512) NOT NULL,
    mime_type VARCHAR(255),
    extension VARCHAR(32),
    is_directory BOOLEAN NOT NULL,
    size_bytes BIGINT NOT NULL,
    modified_at TIMESTAMP,
    etag VARCHAR(255),
    indexed_at TIMESTAMP NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (drive_id, remote_path)
);

CREATE TABLE IF NOT EXISTS media_package (
    id BIGINT PRIMARY KEY,
    package_key VARCHAR(64) NOT NULL UNIQUE,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(255),
    directory_path VARCHAR(1024) NOT NULL UNIQUE,
    primary_file_id BIGINT,
    display_name VARCHAR(255) NOT NULL,
    summary VARCHAR(500),
    description CLOB,
    import_status VARCHAR(32) NOT NULL,
    analysis_status VARCHAR(32) NOT NULL,
    pipeline_version VARCHAR(64),
    manifest_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS media_package_asset (
    id BIGINT PRIMARY KEY,
    package_id BIGINT NOT NULL,
    local_file_id BIGINT,
    asset_role VARCHAR(32) NOT NULL,
    relative_path VARCHAR(512) NOT NULL,
    sequence_no INT,
    timestamp_ms BIGINT,
    content_hash CHAR(64),
    created_at TIMESTAMP NOT NULL,
    UNIQUE (package_id, relative_path),
    UNIQUE (package_id, asset_role, sequence_no)
);

CREATE TABLE IF NOT EXISTS media_tag_artifact (
    id BIGINT PRIMARY KEY,
    local_file_id BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    producer VARCHAR(32) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(255) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    input_kind VARCHAR(64) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    generated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (content_hash, prompt_version, input_fingerprint)
);

-- 错误码表
CREATE TABLE IF NOT EXISTS t_error_code (
    id BIGINT PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    error_key VARCHAR(50) NOT NULL,
    message VARCHAR(255) NOT NULL,
    category VARCHAR(20) NOT NULL,
    create_time TIMESTAMP NOT NULL
);

-- 初始化默认角色
INSERT INTO t_role (id, role_name, description, create_time) VALUES
(1, 'ADMIN', '管理员', CURRENT_TIMESTAMP),
(2, 'USER', '普通用户', CURRENT_TIMESTAMP);

-- 初始化错误码
INSERT INTO t_error_code (id, code, error_key, message, category) VALUES
(1, 'USER_001', 'USER_NOT_FOUND', '用户不存在', 'USER'),
(2, 'USER_002', 'USERNAME_EXISTS', '用户名已存在', 'USER'),
(3, 'USER_003', 'PASSWORD_INVALID', '密码不符合规范', 'USER'),
(4, 'USER_004', 'EMAIL_INVALID', '邮箱格式错误', 'USER'),
(5, 'USER_005', 'CREDENTIALS_INVALID', '用户名或密码错误', 'USER'),
(6, 'USER_006', 'ACCOUNT_DISABLED', '账户已禁用', 'USER'),
(7, 'USER_007', 'EMAIL_EXISTS', '邮箱已被使用', 'USER'),
(8, 'USER_008', 'OLD_PASSWORD_ERROR', '旧密码错误', 'USER'),
(9, 'USER_009', 'INVALID_STATUS', '无效的用户状态', 'USER'),
(10, 'AUTH_001', 'TOKEN_EXPIRED', 'Token已过期', 'AUTH'),
(11, 'AUTH_002', 'TOKEN_INVALID', '无效Token', 'AUTH'),
(12, 'AUTH_003', 'ACCESS_DENIED', '权限不足', 'AUTH'),
(13, 'AUTH_004', 'TOKEN_FORMAT_ERROR', 'Token格式错误', 'AUTH'),
(14, 'AUTH_005', 'TOKEN_REFRESH_FAILED', 'Token刷新失败', 'AUTH'),
(15, 'SYS_001', 'INTERNAL_ERROR', '系统内部错误', 'SYS'),
(16, 'SYS_002', 'VALIDATION_FAILED', '参数校验失败', 'SYS'),
(17, 'SYS_003', 'DB_OPERATION_FAILED', '数据库操作失败', 'SYS');

CREATE TABLE IF NOT EXISTS t_dsh_session_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    dsh_session_id VARCHAR(128) NOT NULL UNIQUE,
    workspace_key VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_seq BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
