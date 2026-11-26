ALTER TABLE news_source ADD COLUMN capacity_recorded BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE news_source SET capacity_recorded=TRUE;
ALTER TABLE news_source ADD CONSTRAINT ck_news_source_capacity CHECK (capacity_recorded=TRUE);
ALTER TABLE news_region_source_pack ADD COLUMN capacity_recorded BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE news_region_source_pack SET capacity_recorded=TRUE;
ALTER TABLE news_region_source_pack ADD CONSTRAINT ck_news_pack_capacity CHECK (capacity_recorded=TRUE);
ALTER TABLE news_region_schedule ADD COLUMN capacity_recorded BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE news_region_schedule SET capacity_recorded=TRUE;
ALTER TABLE news_region_schedule ADD CONSTRAINT ck_news_node_capacity CHECK (capacity_recorded=TRUE);
ALTER TABLE news_region_job ADD COLUMN capacity_recorded BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE news_region_job SET capacity_recorded=TRUE;
ALTER TABLE news_region_job ADD CONSTRAINT ck_news_region_job_capacity CHECK (capacity_recorded=TRUE);
ALTER TABLE news_region_observation ADD COLUMN region_job_id CHAR(36);
UPDATE news_region_observation SET region_job_id=(SELECT j.id FROM news_region_job j WHERE j.observation_id=news_region_observation.id);
ALTER TABLE news_region_observation ADD CONSTRAINT uk_news_region_job_observation UNIQUE(region_job_id);
ALTER TABLE news_region_observation ADD CONSTRAINT fk_news_observation_job FOREIGN KEY(region_job_id) REFERENCES news_region_job(id);
ALTER TABLE news_region_observation ADD COLUMN capacity_kind VARCHAR(16) NOT NULL DEFAULT 'UNMETERED';
UPDATE news_region_observation SET capacity_kind=CASE WHEN region_job_id IS NULL THEN 'DIRECT' ELSE 'JOB' END;
ALTER TABLE news_region_observation ADD CONSTRAINT ck_news_observation_capacity CHECK ((capacity_kind='DIRECT' AND region_job_id IS NULL) OR (capacity_kind='JOB' AND region_job_id IS NOT NULL));
ALTER TABLE news_source_poll ADD CONSTRAINT fk_news_poll_source FOREIGN KEY(source_id) REFERENCES news_source(id);
CREATE TABLE news_registry_capacity (
    id INT PRIMARY KEY,
    source_rows BIGINT NOT NULL,
    pack_rows BIGINT NOT NULL,
    node_rows BIGINT NOT NULL,
    region_jobs BIGINT NOT NULL,
    direct_observations BIGINT NOT NULL,
    CONSTRAINT ck_news_registry_capacity CHECK (id=1 AND source_rows>=0 AND pack_rows>=0 AND node_rows>=0 AND region_jobs>=0 AND direct_observations>=0)
);
INSERT INTO news_registry_capacity(id,source_rows,pack_rows,node_rows,region_jobs,direct_observations)
SELECT 1,(SELECT COUNT(*) FROM news_source),(SELECT COUNT(*) FROM news_region_source_pack),
       (SELECT COUNT(*) FROM news_region_schedule),(SELECT COUNT(*) FROM news_region_job),
       (SELECT COUNT(*) FROM news_region_observation WHERE capacity_kind='DIRECT');
