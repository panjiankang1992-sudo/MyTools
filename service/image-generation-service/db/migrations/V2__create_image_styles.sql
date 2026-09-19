-- 风格身份与不可变版本分开保存，删除不破坏历史任务。
CREATE TABLE image_style (
 id CHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, current_version INT NOT NULL,
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_image_style_owner ON image_style(owner_id,deleted,created_at);
CREATE TABLE image_style_version (
 style_id CHAR(36) NOT NULL, version INT NOT NULL, snapshot_json TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(style_id,version),
 FOREIGN KEY(style_id) REFERENCES image_style(id)
);
