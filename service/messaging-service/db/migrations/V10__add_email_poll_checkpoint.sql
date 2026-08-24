CREATE TABLE email_poll_checkpoint (
    account_key VARCHAR(64) NOT NULL,
    mailbox_name VARCHAR(255) NOT NULL,
    uid_validity BIGINT NOT NULL,
    last_uid BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (account_key, mailbox_name)
);
