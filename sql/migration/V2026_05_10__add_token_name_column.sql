-- Token表添加 token_name 列
-- 支持用户自定义 Token 名称

ALTER TABLE t_token
ADD COLUMN token_name VARCHAR(100) DEFAULT NULL COMMENT 'Token名称（用户自定义）' AFTER status;
