-- 为本地文件增加软删除状态，物理文件消失后保留历史记录但不再对外展示。
ALTER TABLE local_file
    ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已从文件系统删除：0-否，1-是' AFTER tagging_status,
    ADD INDEX idx_local_file_deleted_path (deleted, file_path(255));
