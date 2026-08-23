ALTER TABLE shelf_book ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shelf_book ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE reading_progress ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reader_marker ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reader_marker ADD COLUMN version BIGINT NOT NULL DEFAULT 1;

CREATE INDEX idx_shelf_book_owner_updated ON shelf_book (owner_id, updated_at);
CREATE INDEX idx_reader_marker_shelf_updated ON reader_marker (shelf_book_id, updated_at);
