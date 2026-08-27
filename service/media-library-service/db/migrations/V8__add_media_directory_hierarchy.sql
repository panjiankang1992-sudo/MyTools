ALTER TABLE media_directory ADD COLUMN parent_id CHAR(36) NULL AFTER owner_id;
ALTER TABLE media_directory ADD CONSTRAINT fk_media_directory_parent FOREIGN KEY(parent_id) REFERENCES media_directory(id);
CREATE INDEX idx_media_directory_parent ON media_directory(owner_id,parent_id,name);
