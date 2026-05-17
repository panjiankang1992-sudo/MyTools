-- 应用市场主表
CREATE TABLE IF NOT EXISTS t_app_market (
    id VARCHAR(19) NOT NULL PRIMARY KEY COMMENT '主键 Snowflake ID',
    user_id BIGINT NOT NULL COMMENT '发布人用户ID',
    name VARCHAR(100) NOT NULL COMMENT '应用名称',
    type VARCHAR(20) NOT NULL COMMENT 'app/cli/mcp/skill',
    version VARCHAR(50) NOT NULL DEFAULT '1.0.0' COMMENT '当前版本号',
    thumbnail_id VARCHAR(19) DEFAULT NULL COMMENT '缩略图文件ID',
    content TEXT COMMENT '应用简介(富文本HTML)',
    install_cmd VARCHAR(500) DEFAULT NULL COMMENT '安装命令',
    download_url VARCHAR(500) DEFAULT NULL COMMENT '外部下载链接',
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/DRAFT',
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_type (type),
    INDEX idx_status (status),
    INDEX idx_name (name),
    INDEX idx_created_time (created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用市场主表';

-- 历史版本表
CREATE TABLE IF NOT EXISTS t_app_version (
    id VARCHAR(19) NOT NULL PRIMARY KEY COMMENT '主键',
    app_id VARCHAR(19) NOT NULL COMMENT '所属应用ID',
    version VARCHAR(50) NOT NULL COMMENT '版本号',
    content TEXT COMMENT '该版本的简介',
    file_id VARCHAR(19) DEFAULT NULL COMMENT '该版本的文件ID',
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    INDEX idx_app_id (app_id),
    INDEX idx_version (app_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用历史版本表';

-- 应用文件表
CREATE TABLE IF NOT EXISTS t_app_file (
    id VARCHAR(19) NOT NULL PRIMARY KEY COMMENT '主键',
    app_id VARCHAR(19) NOT NULL COMMENT '所属应用ID',
    version_id VARCHAR(19) DEFAULT NULL COMMENT '所属版本ID(可为null表示当前版本)',
    file_type VARCHAR(20) NOT NULL COMMENT 'thumbnail/binary/json/zip/html',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_path VARCHAR(500) NOT NULL COMMENT '存储路径',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    INDEX idx_app_id (app_id),
    INDEX idx_version_id (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用文件表';
