ALTER TABLE legacy_inbound_export_snapshot
    ADD COLUMN protocol_version VARCHAR(32) NOT NULL DEFAULT 'adapter-payload-v1';

ALTER TABLE legacy_inbound_export_snapshot
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (high_water_sequence, protocol_version);
