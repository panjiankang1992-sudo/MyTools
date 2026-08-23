CREATE TABLE onebot_account (
    id CHAR(36) PRIMARY KEY,
    external_key VARCHAR(128) NOT NULL UNIQUE,
    http_base_url VARCHAR(512) NOT NULL,
    secret_ref VARCHAR(512) NOT NULL,
    host_qq_root VARCHAR(1024) NOT NULL,
    container_qq_root VARCHAR(1024) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
