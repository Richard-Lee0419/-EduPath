ALTER TABLE resource_quality_repairs ADD COLUMN candidate_base_version INT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN previous_resource_snapshot TEXT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN previous_resource_version INT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN published_resource_version INT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN published_by BIGINT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN publish_note VARCHAR(500) NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN published_at TIMESTAMP NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN rollback_resource_version INT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN rolled_back_by BIGINT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN rollback_note VARCHAR(500) NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN rolled_back_at TIMESTAMP NULL;

CREATE INDEX idx_quality_repairs_published_version
    ON resource_quality_repairs(resource_id, published_resource_version);
