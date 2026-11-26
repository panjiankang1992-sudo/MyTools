ALTER TABLE messaging_destination ADD COLUMN capacity_recorded BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE messaging_destination SET capacity_recorded=TRUE;
ALTER TABLE messaging_destination ADD CONSTRAINT ck_destination_capacity CHECK (capacity_recorded=TRUE);

ALTER TABLE messaging_outbound ADD COLUMN capacity_kind VARCHAR(16) NOT NULL DEFAULT 'UNMETERED';
ALTER TABLE messaging_outbound ADD COLUMN capacity_bytes BIGINT NOT NULL DEFAULT 0;
UPDATE messaging_outbound SET capacity_kind='DELIVERY',capacity_bytes=COALESCE(OCTET_LENGTH(content_json),0);
ALTER TABLE messaging_outbound ADD CONSTRAINT ck_outbound_capacity_kind CHECK (capacity_kind IN ('DELIVERY','CANCEL_ONLY'));
ALTER TABLE messaging_outbound ADD CONSTRAINT ck_outbound_capacity_bytes CHECK (capacity_bytes>=0 AND capacity_bytes>=COALESCE(OCTET_LENGTH(content_json),0) AND (content_json IS NOT NULL OR capacity_bytes=0));
ALTER TABLE messaging_outbound ADD CONSTRAINT ck_outbound_cancel_only CHECK (capacity_kind<>'CANCEL_ONLY' OR (status='CANCELLED' AND generation=1 AND content_json IS NULL AND content_identity_hash IS NOT NULL AND content_expired_ms IS NOT NULL));
ALTER TABLE messaging_outbound ADD CONSTRAINT ck_outbound_generation CHECK (generation BETWEEN 1 AND 100);
ALTER TABLE messaging_outbound_attempt ADD CONSTRAINT ck_outbound_attempt_generation CHECK (generation BETWEEN 1 AND 100);
ALTER TABLE messaging_outbound_attempt ADD CONSTRAINT fk_outbound_attempt_delivery FOREIGN KEY(delivery_id) REFERENCES messaging_outbound(id);

CREATE TABLE messaging_outbound_capacity (
    id INT PRIMARY KEY,
    destination_rows BIGINT NOT NULL,
    delivery_rows BIGINT NOT NULL,
    cancel_only_rows BIGINT NOT NULL,
    payload_bytes BIGINT NOT NULL,
    CONSTRAINT ck_outbound_capacity_ledger CHECK (id=1 AND destination_rows>=0 AND delivery_rows>=0 AND cancel_only_rows>=0 AND payload_bytes>=0)
);
INSERT INTO messaging_outbound_capacity(id,destination_rows,delivery_rows,cancel_only_rows,payload_bytes)
SELECT 1,(SELECT COUNT(*) FROM messaging_destination),COUNT(*),0,COALESCE(SUM(capacity_bytes),0) FROM messaging_outbound;
