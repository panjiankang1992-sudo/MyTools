CREATE TABLE IF NOT EXISTS t_dsh_session_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    dsh_session_id VARCHAR(128) NOT NULL,
    workspace_key VARCHAR(64) NOT NULL DEFAULT 'default',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_seq BIGINT NOT NULL DEFAULT -1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dsh_session_id (dsh_session_id),
    KEY idx_dsh_session_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
