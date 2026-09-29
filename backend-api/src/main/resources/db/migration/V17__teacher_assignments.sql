CREATE TABLE teacher_assignments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    teacher_user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    instructions TEXT NULL,
    resource_id VARCHAR(128) NULL,
    due_at TIMESTAMP NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    published_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_teacher_assignment_class FOREIGN KEY (class_id) REFERENCES classes(id),
    CONSTRAINT fk_teacher_assignment_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT fk_teacher_assignment_teacher FOREIGN KEY (teacher_user_id) REFERENCES users(id)
);

CREATE TABLE teacher_assignment_targets (
    assignment_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'assigned',
    completed_at TIMESTAMP NULL,
    score DECIMAL(5,2) NULL,
    PRIMARY KEY (assignment_id, student_id),
    CONSTRAINT fk_assignment_target_assignment FOREIGN KEY (assignment_id) REFERENCES teacher_assignments(id),
    CONSTRAINT fk_assignment_target_student FOREIGN KEY (student_id) REFERENCES users(id)
);

CREATE INDEX idx_teacher_assignments_class_status ON teacher_assignments(class_id, status);
CREATE INDEX idx_assignment_targets_student_status ON teacher_assignment_targets(student_id, status);
