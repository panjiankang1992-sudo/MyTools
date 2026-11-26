-- 公开门面数据库结构：匿名会话、使用事件、统计聚合，以及后续 PDF 里程碑预留的资源表。
-- 说明：本文件只使用可移植 DDL（H2 MySQL 兼容模式可执行），表选项保留 InnoDB 与 utf8mb4。
-- 预留表（toolbox_upload/toolbox_task/toolbox_task_input/toolbox_cleanup/toolbox_execution_slot/toolbox_outbox）
-- 由后续公开 PDF 里程碑使用，当前 Java 代码不引用。

-- 匿名访客会话：服务端只保存 token 的 SHA-256，原始 token 只经 cookie 返回浏览器。
CREATE TABLE toolbox_guest (
  owner_id BIGINT NOT NULL AUTO_INCREMENT,
  guest_id CHAR(36) NOT NULL,
  token_hash BINARY(32) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP NULL,
  PRIMARY KEY (owner_id),
  UNIQUE (guest_id),
  UNIQUE (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 匿名使用事件明细，site_id 预留多站点隔离。
CREATE TABLE toolbox_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  site_id VARCHAR(64) NOT NULL,
  guest_id CHAR(36) NOT NULL,
  event_id VARCHAR(64) NOT NULL,
  tool_id VARCHAR(64) NULL,
  category VARCHAR(16) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  source VARCHAR(16) NOT NULL,
  operation_id VARCHAR(64) NULL,
  occurred_at TIMESTAMP NOT NULL,
  received_at TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (site_id, guest_id, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_toolbox_event_received ON toolbox_event (received_at);

-- 成功语义去重：同一访客同一 toolId + operationId 只累计一次成功。
CREATE TABLE toolbox_success_dedupe (
  site_id VARCHAR(64) NOT NULL,
  source VARCHAR(16) NOT NULL,
  guest_id CHAR(36) NOT NULL,
  tool_id VARCHAR(64) NOT NULL,
  operation_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (site_id, source, guest_id, tool_id, operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 统计 Outbox：与事件写入同事务，聚合任务按主键顺序消费。
CREATE TABLE toolbox_stat_outbox (
  event_pk BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  lease_until TIMESTAMP NULL,
  PRIMARY KEY (event_pk)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_toolbox_stat_outbox_state ON toolbox_stat_outbox (state, event_pk);

-- 已聚合事件标记：唯一主键保证重复消费不重复计数。
CREATE TABLE toolbox_stat_applied (
  event_pk BIGINT NOT NULL,
  applied_at TIMESTAMP NOT NULL,
  PRIMARY KEY (event_pk)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 按 Asia/Shanghai 自然日聚合的长期计数，tool_id_key 使用 '__category__' 表示仅分类的入口事件。
CREATE TABLE toolbox_daily_stat (
  local_date DATE NOT NULL,
  category VARCHAR(16) NOT NULL,
  tool_id_key VARCHAR(64) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (local_date, category, tool_id_key, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_toolbox_daily_stat_type ON toolbox_daily_stat (event_type, local_date);

-- 聚合水位：complete_through 之前的 received_at 已全部计入 daily_stat。
CREATE TABLE toolbox_stat_cursor (
  site_id VARCHAR(64) NOT NULL,
  complete_through TIMESTAMP NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (site_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 以下为公开 PDF 里程碑预留结构，当前代码不写入也不读取。

CREATE TABLE toolbox_upload (
  id CHAR(36) NOT NULL,
  owner_id BIGINT NOT NULL,
  upstream_input_id VARCHAR(128) NULL,
  status VARCHAR(24) NOT NULL,
  declared_bytes BIGINT NOT NULL,
  confirmed_offset BIGINT NOT NULL DEFAULT 0,
  media_type VARCHAR(128) NULL,
  display_name VARCHAR(255) NULL,
  sha256 CHAR(64) NULL,
  page_count INT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_digest CHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (owner_id, idempotency_key),
  FOREIGN KEY (owner_id) REFERENCES toolbox_guest (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_toolbox_upload_expires ON toolbox_upload (expires_at);

CREATE TABLE toolbox_task (
  id CHAR(36) NOT NULL,
  owner_id BIGINT NOT NULL,
  tool_id VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  phase VARCHAR(24) NULL,
  client_revision INT NOT NULL DEFAULT 0,
  canonical_request_json LONGTEXT NULL,
  request_digest CHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  upstream_plan_id VARCHAR(128) NULL,
  upstream_job_id VARCHAR(128) NULL,
  queue_deadline TIMESTAMP NULL,
  dispatched_at TIMESTAMP NULL,
  execution_deadline TIMESTAMP NULL,
  publish_deadline TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP NULL,
  expires_at TIMESTAMP NOT NULL,
  error_code VARCHAR(32) NULL,
  PRIMARY KEY (id),
  UNIQUE (owner_id, idempotency_key),
  FOREIGN KEY (owner_id) REFERENCES toolbox_guest (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_toolbox_task_owner_status ON toolbox_task (owner_id, status, created_at);

CREATE TABLE toolbox_task_input (
  task_id CHAR(36) NOT NULL,
  upload_id CHAR(36) NOT NULL,
  input_role VARCHAR(16) NOT NULL,
  ordinal INT NOT NULL,
  PRIMARY KEY (task_id, input_role, ordinal),
  FOREIGN KEY (task_id) REFERENCES toolbox_task (id),
  FOREIGN KEY (upload_id) REFERENCES toolbox_upload (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE toolbox_cleanup (
  id BIGINT NOT NULL AUTO_INCREMENT,
  managed_object_id VARCHAR(128) NOT NULL,
  state VARCHAR(24) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  not_before TIMESTAMP NOT NULL,
  last_error_code VARCHAR(32) NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_toolbox_cleanup_due ON toolbox_cleanup (state, not_before);

CREATE TABLE toolbox_execution_slot (
  slot_id VARCHAR(64) NOT NULL,
  task_id CHAR(36) NULL,
  lease_until TIMESTAMP NULL,
  fencing_version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (slot_id),
  UNIQUE (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE toolbox_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,
  dedupe_key VARCHAR(160) NOT NULL,
  state VARCHAR(24) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP NULL,
  lease_until TIMESTAMP NULL,
  fencing_version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE (dedupe_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
