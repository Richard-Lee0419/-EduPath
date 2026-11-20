CREATE TABLE tree_practice_attempts (
    student_user_id BIGINT NOT NULL,
    exercise_id VARCHAR(64) NOT NULL,
    answer_json TEXT NOT NULL,
    feedback_json TEXT NOT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (student_user_id, exercise_id),
    CONSTRAINT fk_tree_practice_student FOREIGN KEY (student_user_id) REFERENCES users(id) ON DELETE CASCADE
);
