ALTER TABLE resources ADD COLUMN owner_user_id BIGINT NULL;
ALTER TABLE resources ADD COLUMN storage_expires_at TIMESTAMP NULL;
UPDATE resources SET owner_user_id = created_by WHERE owner_user_id IS NULL AND created_by IS NOT NULL;

ALTER TABLE knowledge_documents ADD COLUMN owner_user_id BIGINT NULL;
ALTER TABLE knowledge_documents ADD COLUMN storage_expires_at TIMESTAMP NULL;

CREATE TABLE classes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    owner_user_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_classes_owner FOREIGN KEY (owner_user_id) REFERENCES users(id)
);

CREATE TABLE class_members (
    class_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (class_id, user_id, role),
    CONSTRAINT fk_class_members_class FOREIGN KEY (class_id) REFERENCES classes(id),
    CONSTRAINT fk_class_members_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE class_courses (
    class_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (class_id, course_id),
    CONSTRAINT fk_class_courses_class FOREIGN KEY (class_id) REFERENCES classes(id),
    CONSTRAINT fk_class_courses_course FOREIGN KEY (course_id) REFERENCES courses(id)
);

CREATE INDEX idx_classes_owner ON classes(owner_user_id);
CREATE INDEX idx_class_members_user ON class_members(user_id);
CREATE INDEX idx_class_courses_course ON class_courses(course_id);
