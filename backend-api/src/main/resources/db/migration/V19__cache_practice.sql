CREATE TABLE cache_practice_attempts (
    student_user_id BIGINT NOT NULL,
    exercise_id VARCHAR(64) NOT NULL,
    tag_bits INT NOT NULL,
    index_bits INT NOT NULL,
    offset_bits INT NOT NULL,
    feedback_json TEXT NOT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (student_user_id, exercise_id),
    CONSTRAINT fk_cache_practice_student FOREIGN KEY (student_user_id) REFERENCES users(id) ON DELETE CASCADE
);
