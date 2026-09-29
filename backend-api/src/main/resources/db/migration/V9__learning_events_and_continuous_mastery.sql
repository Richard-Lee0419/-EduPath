ALTER TABLE knowledge_mastery ADD COLUMN behavior_event_count INT NOT NULL DEFAULT 0;
ALTER TABLE knowledge_mastery ADD COLUMN behavior_weight INT NOT NULL DEFAULT 0;
ALTER TABLE knowledge_mastery ADD COLUMN behavior_score_sum INT NOT NULL DEFAULT 0;
ALTER TABLE knowledge_mastery ADD COLUMN last_event_type VARCHAR(64) NULL;
ALTER TABLE knowledge_mastery ADD COLUMN last_event_at TIMESTAMP NULL;

CREATE TABLE learning_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(96) NOT NULL UNIQUE,
    idempotency_key VARCHAR(255) NOT NULL,
    student_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    course_id BIGINT NOT NULL,
    knowledge_point VARCHAR(255) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    progress_percent INT NULL,
    sequence_no INT NULL,
    evidence_weight INT NOT NULL DEFAULT 0,
    evidence_score INT NOT NULL DEFAULT 0,
    metadata TEXT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_learning_events_idempotency UNIQUE (student_id, idempotency_key)
);

CREATE INDEX idx_learning_events_student_time ON learning_events(student_id, occurred_at);
CREATE INDEX idx_learning_events_source ON learning_events(student_id, source_type, source_id);
CREATE INDEX idx_learning_events_knowledge ON learning_events(student_id, knowledge_point);
