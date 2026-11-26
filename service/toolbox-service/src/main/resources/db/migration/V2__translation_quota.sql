-- 翻译用量在服务重启后保留，不存输入正文、输出正文、Cookie 或上游凭据。
CREATE TABLE toolbox_translation_quota (
  quota_day DATE NOT NULL,
  quota_scope VARCHAR(64) NOT NULL,
  used_characters INT NOT NULL,
  PRIMARY KEY (quota_day, quota_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
