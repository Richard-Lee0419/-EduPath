CREATE TABLE teacher_invites (
    code_hash VARCHAR(64) PRIMARY KEY,
    email VARCHAR(255) NULL,
    created_by BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP NULL,
    CONSTRAINT fk_teacher_invites_issuer FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE review_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    teacher_user_id BIGINT NOT NULL UNIQUE,
    student_user_id BIGINT NOT NULL UNIQUE,
    class_id BIGINT NOT NULL UNIQUE,
    created_by BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    CONSTRAINT fk_review_links_teacher FOREIGN KEY (teacher_user_id) REFERENCES users(id),
    CONSTRAINT fk_review_links_student FOREIGN KEY (student_user_id) REFERENCES users(id),
    CONSTRAINT fk_review_links_class FOREIGN KEY (class_id) REFERENCES classes(id),
    CONSTRAINT fk_review_links_issuer FOREIGN KEY (created_by) REFERENCES users(id)
);
