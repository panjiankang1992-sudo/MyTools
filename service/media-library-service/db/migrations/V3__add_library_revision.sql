CREATE TABLE media_library_revision (
    singleton_id SMALLINT PRIMARY KEY,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ck_media_library_revision_singleton CHECK(singleton_id=1)
);

INSERT INTO media_library_revision(singleton_id,revision,updated_at)
VALUES (1,0,CURRENT_TIMESTAMP);
