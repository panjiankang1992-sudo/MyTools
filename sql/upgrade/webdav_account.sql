CREATE TABLE webdav_account (
    id           BIGINT        NOT NULL  AUTO_INCREMENT  PRIMARY KEY COMMENT '主键',
    user_id      BIGINT        NOT NULL  UNIQUE COMMENT '关联用户ID',
    type         VARCHAR(32)   NOT NULL  DEFAULT 'jianguoyun' COMMENT '服务类型',
    url          VARCHAR(512)  NOT NULL  COMMENT 'WebDAV地址',
    username     VARCHAR(128)  NOT NULL  COMMENT '用户名',
    password     VARCHAR(256)  NOT NULL  COMMENT '密码(AES加密)',
    is_active    TINYINT       NOT NULL  DEFAULT 1 COMMENT '是否启用',
    create_time  DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WebDAV账号表';
