CREATE TABLE rss_owner (
    owner_id BIGINT PRIMARY KEY,
    discover_after BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL
);
CREATE TABLE rss_group (
    id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    UNIQUE (owner_id, name),
    UNIQUE (owner_id, id),
    FOREIGN KEY (owner_id) REFERENCES rss_owner(owner_id)
);
CREATE TABLE rss_subscription (
    id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    group_id VARCHAR(36),
    feed_url VARCHAR(2048) NOT NULL,
    feed_url_hash VARCHAR(64) NOT NULL,
    title VARCHAR(256) NOT NULL,
    custom_title VARCHAR(256) NOT NULL DEFAULT '',
    site_url VARCHAR(2048) NOT NULL DEFAULT '',
    description VARCHAR(1000) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    interval_minutes INT NOT NULL DEFAULT 30,
    initial_read_all BOOLEAN NOT NULL DEFAULT FALSE,
    initialized BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    last_attempt_at BIGINT,
    last_success_at BIGINT,
    next_fetch_at BIGINT NOT NULL,
    retry_after BIGINT NOT NULL DEFAULT 0,
    request_after BIGINT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(16),
    etag VARCHAR(2048),
    last_modified VARCHAR(2048),
    lease_token VARCHAR(36),
    lease_until BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    unsubscribed_at BIGINT,
    UNIQUE (owner_id, feed_url_hash),
    UNIQUE (owner_id, id),
    FOREIGN KEY (owner_id) REFERENCES rss_owner(owner_id),
    FOREIGN KEY (owner_id, group_id) REFERENCES rss_group(owner_id, id)
);
CREATE INDEX rss_subscription_due ON rss_subscription(status, next_fetch_at, lease_until);
CREATE TABLE rss_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    subscription_id VARCHAR(36) NOT NULL,
    identity_type VARCHAR(16) NOT NULL,
    identity_hash VARCHAR(64) NOT NULL,
    source_id VARCHAR(2048) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    title VARCHAR(1000) NOT NULL,
    author VARCHAR(256) NOT NULL,
    summary_text VARCHAR(2400) NOT NULL,
    content_html LONGTEXT NOT NULL,
    image_url VARCHAR(2048) NOT NULL,
    published_at BIGINT,
    source_updated_at BIGINT,
    discovered_at BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    content_purged_at BIGINT,
    UNIQUE (subscription_id, identity_type, identity_hash),
    UNIQUE (owner_id, id),
    FOREIGN KEY (owner_id, subscription_id) REFERENCES rss_subscription(owner_id, id)
);
CREATE INDEX rss_entry_owner_page ON rss_entry(owner_id, id);
CREATE INDEX rss_entry_subscription_page ON rss_entry(subscription_id, id);
CREATE TABLE rss_entry_state (
    owner_id BIGINT NOT NULL,
    entry_id BIGINT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at BIGINT,
    starred_at BIGINT,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_id, entry_id),
    FOREIGN KEY (owner_id, entry_id) REFERENCES rss_entry(owner_id, id)
);
CREATE INDEX rss_state_unread ON rss_entry_state(owner_id, is_read, entry_id);
CREATE INDEX rss_state_starred ON rss_entry_state(owner_id, starred_at, entry_id);
