ALTER TABLE download_request
    ADD COLUMN owner_id BIGINT NOT NULL DEFAULT 0 AFTER id;

UPDATE download_request
SET owner_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.ownerId')) AS UNSIGNED)
WHERE JSON_EXTRACT(parameters_json, '$.ownerId') IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.ownerId')) REGEXP '^[0-9]+$';

CREATE INDEX idx_download_request_owner_created
    ON download_request (owner_id, created_at);
