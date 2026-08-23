CREATE TABLE drive_index_batch (
    account_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    batch_key VARCHAR(255) NOT NULL,
    item_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (account_id, run_id, batch_key),
    CONSTRAINT fk_drive_batch_account FOREIGN KEY (account_id) REFERENCES drive_account(id)
);
