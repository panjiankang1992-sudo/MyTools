CREATE TABLE messaging_channel_budget (
    bucket_key CHAR(64) PRIMARY KEY,
    next_start_ms BIGINT NOT NULL
);
