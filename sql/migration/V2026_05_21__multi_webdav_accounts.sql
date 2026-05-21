-- 移除唯一索引，支持一个用户拥有多个 WebDAV 账号
ALTER TABLE webdav_account DROP INDEX idx_user_id;

-- 添加账号名称字段
ALTER TABLE webdav_account ADD COLUMN name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '账号名称' AFTER type;

-- 添加默认账号标记
ALTER TABLE webdav_account ADD COLUMN is_default TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认' AFTER is_active;

-- 重建普通索引（非唯一）
ALTER TABLE webdav_account ADD INDEX idx_user_id (user_id);

-- 将现有记录的 name 设为 type 对应的中文名
UPDATE webdav_account SET name = '坚果云' WHERE type = 'jianguoyun' AND (name IS NULL OR name = '');

-- 将现有唯一账号设为默认
UPDATE webdav_account SET is_default = 1 WHERE is_default = 0;
