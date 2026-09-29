ALTER TABLE resource_quality_repairs ADD COLUMN execution_task_id VARCHAR(64) NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN candidate_resource TEXT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN candidate_safety TEXT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN candidate_quality_evaluation TEXT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN candidate_model_runtime TEXT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN candidate_generation_mode VARCHAR(32) NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN publish_ready BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE resource_quality_repairs ADD COLUMN execution_error TEXT NULL;
ALTER TABLE resource_quality_repairs ADD COLUMN executed_at TIMESTAMP NULL;

CREATE INDEX idx_quality_repairs_execution_task ON resource_quality_repairs(execution_task_id);
CREATE INDEX idx_quality_repairs_publish_ready ON resource_quality_repairs(publish_ready, status);
