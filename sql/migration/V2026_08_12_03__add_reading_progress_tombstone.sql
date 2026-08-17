ALTER TABLE t_reading_progress
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE AFTER server_updated_at;
