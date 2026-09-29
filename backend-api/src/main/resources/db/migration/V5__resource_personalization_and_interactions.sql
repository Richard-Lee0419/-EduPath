ALTER TABLE resources ADD COLUMN personalized_reason TEXT NULL;
ALTER TABLE resources ADD COLUMN estimated_minutes INT NULL;
ALTER TABLE resources ADD COLUMN profile_fingerprint VARCHAR(64) NULL;

CREATE TABLE resource_interactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    rating INT NULL,
    progress_percent INT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_resource_interactions_user (user_id, created_at),
    INDEX idx_resource_interactions_resource (resource_id, created_at)
);
