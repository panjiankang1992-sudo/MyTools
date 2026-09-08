ALTER TABLE audiobook_generation
    ADD COLUMN analysis_model_version VARCHAR(128);

ALTER TABLE audiobook_generation
    ADD COLUMN analysis_rule_version VARCHAR(64);
