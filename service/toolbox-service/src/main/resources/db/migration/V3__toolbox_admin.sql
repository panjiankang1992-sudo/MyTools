-- 管理员门面的持久化结构（设计文档第 5.2 节）。
-- 只新增表，不修改任何既有表；已发布正文以不可变版本保存，发布后不允许 UPDATE 正文。

-- 独立管理会话：浏览器只持有随机 Cookie，库中只存哈希；上游令牌加密保存。
CREATE TABLE toolbox_admin_session (
  token_hash VARCHAR(64) NOT NULL,
  mytools_user_id VARCHAR(64) NOT NULL,
  username VARCHAR(128) NULL,
  encrypted_access TEXT NOT NULL,
  encrypted_refresh TEXT NULL,
  access_expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  last_activity_at TIMESTAMP NOT NULL,
  absolute_expires_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP NULL,
  refresh_revision BIGINT NOT NULL DEFAULT 0,
  -- 管理写请求的 CSRF 令牌哈希，与会话绑定；不保存明文。
  csrf_token_hash VARCHAR(64) NOT NULL,
  PRIMARY KEY (token_hash),
  KEY idx_admin_session_user (mytools_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 登录预认证挑战：一次性 nonce，绑定预认证 Cookie；不保存账号或密码。
CREATE TABLE toolbox_admin_login_challenge (
  cookie_hash VARCHAR(64) NOT NULL,
  csrf_hash VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  consumed_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (cookie_hash),
  KEY idx_admin_challenge_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 教材管理列表：一个 edition 一条记录，修订版本不会变成重复教材。
CREATE TABLE toolbox_textbook (
  edition_id VARCHAR(128) NOT NULL,
  current_version_id VARCHAR(64) NULL,
  draft_id VARCHAR(64) NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (edition_id),
  KEY idx_textbook_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 共享草稿：一本教材一条；revision 用于乐观锁 CAS。
CREATE TABLE toolbox_textbook_draft (
  draft_id VARCHAR(64) NOT NULL,
  edition_id VARCHAR(128) NOT NULL,
  base_content_version VARCHAR(32) NULL,
  revision BIGINT NOT NULL,
  editable_json LONGTEXT NOT NULL,
  source_metadata_json LONGTEXT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (draft_id),
  UNIQUE KEY uk_draft_edition (edition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 不可变发布版本：正文以原始字节保存，哈希独立保存，发布后不更新正文。
CREATE TABLE toolbox_textbook_version (
  version_id VARCHAR(64) NOT NULL,
  edition_id VARCHAR(128) NOT NULL,
  content_version VARCHAR(32) NOT NULL,
  content_bytes LONGBLOB NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  edition_metadata_json LONGTEXT NOT NULL,
  source_metadata_json LONGTEXT NULL,
  published_by VARCHAR(64) NOT NULL,
  published_at TIMESTAMP NOT NULL,
  PRIMARY KEY (version_id),
  UNIQUE KEY uk_version_edition_content (edition_id, content_version),
  KEY idx_version_edition (edition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 目录快照世代：发布时行锁并单调递增，用于缓存 ETag 与跨实例一致性比较。
CREATE TABLE toolbox_curriculum_catalog (
  catalog_key VARCHAR(32) NOT NULL,
  epoch BIGINT NOT NULL,
  catalog_version VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (catalog_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 管理审计：只记操作者、对象、版本、动作、摘要与请求标识，不记正文与凭据。
CREATE TABLE toolbox_curriculum_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_user_id VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,
  edition_id VARCHAR(128) NULL,
  draft_id VARCHAR(64) NULL,
  from_revision BIGINT NULL,
  to_revision BIGINT NULL,
  from_version VARCHAR(32) NULL,
  to_version VARCHAR(32) NULL,
  summary VARCHAR(512) NULL,
  request_id VARCHAR(64) NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  KEY idx_audit_edition_time (edition_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 幂等回执：创建／保存／发布都登记；同键同内容返回原结果与完整 ID 映射。
CREATE TABLE toolbox_admin_operation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_user_id VARCHAR(64) NOT NULL,
  operation_scope VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_digest CHAR(64) NOT NULL,
  result_json LONGTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_operation_key (actor_user_id, operation_scope, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 单例目录世代行：首次发布前即存在，供发布时行锁使用。
INSERT INTO toolbox_curriculum_catalog (catalog_key, epoch, catalog_version, updated_at)
VALUES ('current', 0, 'catalog-0', CURRENT_TIMESTAMP);
