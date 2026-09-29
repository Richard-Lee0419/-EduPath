CREATE TABLE resource_quality_repairs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repair_id VARCHAR(64) NOT NULL UNIQUE,
    dedupe_key VARCHAR(96) NOT NULL UNIQUE,
    resource_id VARCHAR(64) NOT NULL,
    source_task_id VARCHAR(64) NOT NULL,
    course_id BIGINT NOT NULL,
    owner_user_id BIGINT NULL,
    trigger_alert_codes TEXT NULL,
    regressed_dimensions TEXT NULL,
    baseline_evaluation TEXT NOT NULL,
    current_evaluation TEXT NOT NULL,
    score_delta DECIMAL(8, 2) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'pending_review',
    reviewed_by BIGINT NULL,
    review_note TEXT NULL,
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_quality_repair_resource FOREIGN KEY (resource_id) REFERENCES resources(resource_id),
    CONSTRAINT fk_quality_repair_task FOREIGN KEY (source_task_id) REFERENCES agent_tasks(task_id)
);

CREATE INDEX idx_quality_repairs_status ON resource_quality_repairs(status, created_at);
CREATE INDEX idx_quality_repairs_course ON resource_quality_repairs(course_id, created_at);
CREATE INDEX idx_quality_repairs_owner ON resource_quality_repairs(owner_user_id, created_at);
