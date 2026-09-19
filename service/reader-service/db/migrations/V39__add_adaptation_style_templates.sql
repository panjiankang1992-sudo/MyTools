CREATE TABLE adaptation_style_template (
    code VARCHAR(64) PRIMARY KEY,
    latest_version INT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ck_style_latest CHECK (latest_version >= 0)
);
CREATE TABLE adaptation_style_template_revision (
    template_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL,
    prompt_text TEXT NOT NULL,
    prompt_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (template_code, version),
    FOREIGN KEY (template_code) REFERENCES adaptation_style_template(code),
    CONSTRAINT ck_style_version CHECK (version > 0)
);
CREATE TABLE adaptation_style_publish_guard (id INT PRIMARY KEY);
INSERT INTO adaptation_style_publish_guard (id) VALUES (1);
CREATE TABLE adaptation_style_publish_receipt (
    idempotency_key VARBINARY(128) PRIMARY KEY,
    request_sha256 CHAR(64) NOT NULL,
    template_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    audit_source VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    FOREIGN KEY (template_code, version) REFERENCES adaptation_style_template_revision(template_code, version)
);
ALTER TABLE novel_chapter_adaptation ADD COLUMN style_template_snapshot_json JSON;
ALTER TABLE novel_chapter_adaptation ADD COLUMN generation_intent_text TEXT;
ALTER TABLE novel_chapter_adaptation ADD COLUMN generation_intent_sha256 CHAR(64);
ALTER TABLE novel_chapter_adaptation ADD CONSTRAINT ck_style_generation_snapshot CHECK (
    (style_template_snapshot_json IS NULL AND generation_intent_text IS NULL AND generation_intent_sha256 IS NULL)
    OR (style_template_snapshot_json IS NOT NULL AND generation_intent_text IS NOT NULL AND generation_intent_sha256 IS NOT NULL));
