ALTER TABLE t_book_source_search_cache
    ADD COLUMN cache_status VARCHAR(24) NOT NULL DEFAULT 'EMPTY' AFTER source_revision;

UPDATE t_book_source_search_cache
SET cache_status = CASE WHEN result_count > 0 THEN 'RESULT' ELSE 'EMPTY' END
WHERE cache_status IS NULL OR cache_status = '' OR cache_status = 'EMPTY';
