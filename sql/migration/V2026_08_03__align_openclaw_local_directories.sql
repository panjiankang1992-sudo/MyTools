-- 将本地文件入口统一到 custom/openclaw 下的分类目录。
CREATE TABLE IF NOT EXISTS local_directory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键 ID',
    directory_name VARCHAR(255) NOT NULL COMMENT '目录名称',
    directory_path VARCHAR(1024) NOT NULL COMMENT '目录路径',
    directory_type VARCHAR(32) NOT NULL COMMENT '目录类型',
    scan_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用扫描',
    last_scan_time TIMESTAMP NULL COMMENT '最后扫描时间',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_local_directory_type (directory_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地文件目录表';

UPDATE local_directory
SET directory_path = '/opt/custom/OpenClaw/ebook',
    directory_name = 'Ebooks',
    update_time = CURRENT_TIMESTAMP
WHERE directory_type = 'EBOOK';

UPDATE local_directory
SET directory_path = '/opt/custom/OpenClaw/big_media',
    directory_name = 'Large Media',
    update_time = CURRENT_TIMESTAMP
WHERE directory_type = 'LARGE_MEDIA';

UPDATE local_directory
SET directory_path = '/opt/custom/OpenClaw/media',
    directory_name = 'Multimedia',
    update_time = CURRENT_TIMESTAMP
WHERE directory_type = 'MULTIMEDIA';

INSERT INTO local_directory
    (directory_name, directory_path, directory_type, scan_enabled, create_time, update_time)
SELECT 'Ebooks', '/opt/custom/OpenClaw/ebook', 'EBOOK', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM local_directory WHERE directory_type = 'EBOOK');

INSERT INTO local_directory
    (directory_name, directory_path, directory_type, scan_enabled, create_time, update_time)
SELECT 'Large Media', '/opt/custom/OpenClaw/big_media', 'LARGE_MEDIA', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM local_directory WHERE directory_type = 'LARGE_MEDIA');

INSERT INTO local_directory
    (directory_name, directory_path, directory_type, scan_enabled, create_time, update_time)
SELECT 'Multimedia', '/opt/custom/OpenClaw/media', 'MULTIMEDIA', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM local_directory WHERE directory_type = 'MULTIMEDIA');
