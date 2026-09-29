ALTER TABLE agent_tasks ADD COLUMN actor_user_id BIGINT NULL;
ALTER TABLE agent_tasks ADD COLUMN actor_username VARCHAR(64) NULL;

ALTER TABLE resources ADD COLUMN created_by BIGINT NULL;
ALTER TABLE resources ADD COLUMN source_task_id VARCHAR(64) NULL;
ALTER TABLE resources ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE resources ADD COLUMN tags TEXT NULL;
ALTER TABLE resources ADD COLUMN deleted_at TIMESTAMP NULL;

CREATE TABLE refresh_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE jwt_revocations (
    jti VARCHAR(96) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_jwt_revocations_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE user_course_roles (
    user_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, course_id, role),
    CONSTRAINT fk_user_course_roles_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE courses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(96) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_points (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    name VARCHAR(255) NOT NULL,
    difficulty VARCHAR(32) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_knowledge_points_course FOREIGN KEY (course_id) REFERENCES courses(id)
);

CREATE TABLE knowledge_index_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    task_id VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    requested_by VARCHAR(64) NULL,
    message TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP NULL,
    CONSTRAINT fk_knowledge_index_jobs_document FOREIGN KEY (document_id) REFERENCES knowledge_documents(document_id)
);

CREATE TABLE knowledge_document_chunks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_id VARCHAR(96) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    source VARCHAR(512) NULL,
    score DOUBLE NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_knowledge_document_chunks_document FOREIGN KEY (document_id) REFERENCES knowledge_documents(document_id)
);

CREATE TABLE learning_paths (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    path_id VARCHAR(96) NOT NULL UNIQUE,
    student_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    path_payload TEXT NOT NULL,
    source_task_id VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tutor_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(96) NOT NULL UNIQUE,
    student_id VARCHAR(64) NOT NULL,
    course_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tutor_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(96) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    evidence TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tutor_messages_session FOREIGN KEY (session_id) REFERENCES tutor_sessions(session_id)
);

CREATE TABLE quiz_attempt_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    attempt_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    submitted_answer VARCHAR(255) NULL,
    correct_answer VARCHAR(255) NOT NULL,
    correct BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_quiz_attempt_items_attempt FOREIGN KEY (attempt_id) REFERENCES quiz_attempts(attempt_id),
    CONSTRAINT fk_quiz_attempt_items_question FOREIGN KEY (question_id) REFERENCES quiz_questions(question_id)
);

CREATE TABLE wrong_question_book (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id VARCHAR(64) NOT NULL,
    quiz_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    knowledge_point VARCHAR(255) NOT NULL,
    mistake_pattern VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_mastery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id VARCHAR(64) NOT NULL,
    knowledge_point VARCHAR(255) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    correct_count INT NOT NULL DEFAULT 0,
    mastery_score INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE resource_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT NULL,
    content TEXT NULL,
    tags TEXT NULL,
    changed_by VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE resource_review_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id VARCHAR(64) NOT NULL,
    reviewer VARCHAR(64) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    comment TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE question_bank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    knowledge_point VARCHAR(255) NOT NULL,
    question_type VARCHAR(64) NOT NULL,
    difficulty VARCHAR(32) NOT NULL,
    question TEXT NOT NULL,
    options TEXT NOT NULL,
    answer VARCHAR(255) NOT NULL,
    explanation TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_by VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_tokens_user_status ON refresh_tokens(user_id, status);
CREATE INDEX idx_user_course_roles_user ON user_course_roles(user_id);
CREATE INDEX idx_knowledge_points_course ON knowledge_points(course_id);
CREATE INDEX idx_learning_paths_student ON learning_paths(student_id, version);
CREATE INDEX idx_tutor_sessions_student ON tutor_sessions(student_id, updated_at);
CREATE INDEX idx_quiz_attempts_student ON quiz_attempts(student_id, submitted_at);
CREATE INDEX idx_wrong_question_book_student ON wrong_question_book(student_id, created_at);
CREATE INDEX idx_knowledge_mastery_student ON knowledge_mastery(student_id, knowledge_point);
CREATE INDEX idx_resource_versions_resource ON resource_versions(resource_id, version);
CREATE INDEX idx_audit_logs_actor ON audit_logs(actor, created_at);

INSERT INTO user_course_roles (user_id, course_id, role) VALUES (2, 1, 'manager');
INSERT INTO user_course_roles (user_id, course_id, role) VALUES (2, 2, 'manager');
INSERT INTO user_course_roles (user_id, course_id, role) VALUES (3, 1, 'manager');
INSERT INTO user_course_roles (user_id, course_id, role) VALUES (3, 2, 'manager');

INSERT INTO courses (id, code, name, description, status)
VALUES (1, 'data_structures_algorithms', '数据结构与算法', '线性表、树、图、排序、查找、递归、动态规划等核心知识', 'active');
INSERT INTO courses (id, code, name, description, status)
VALUES (2, 'computer_organization', '计算机组成原理', '数据表示、运算器、指令系统、CPU、存储系统、Cache、I/O、流水线', 'active');

INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (1, 1, NULL, '数据结构与算法', NULL, 1);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (11, 1, 1, '线性表', 'basic', 1);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (12, 1, 1, '栈与队列', 'basic', 2);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (13, 1, 1, '树与二叉树', 'medium', 3);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (131, 1, 13, '二叉树递归遍历', 'basic', 1);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (132, 1, 13, '二叉搜索树', 'medium', 2);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (133, 1, 13, '堆与优先队列', 'medium', 3);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (14, 1, 1, '图算法', 'advanced', 4);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (141, 1, 14, '图的遍历', 'medium', 1);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (142, 1, 14, '最短路径', 'advanced', 2);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (15, 1, 1, '排序与查找', 'medium', 5);

INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (2, 2, NULL, '计算机组成原理', NULL, 1);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (21, 2, 2, '数据表示', 'basic', 1);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (22, 2, 2, '运算器', 'basic', 2);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (23, 2, 2, '指令系统', 'medium', 3);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (24, 2, 2, 'CPU 与流水线', 'medium', 4);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (25, 2, 2, '存储系统与 Cache', 'medium', 5);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (251, 2, 25, 'Cache 映射方式', 'basic', 1);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (252, 2, 25, '虚拟存储器', 'medium', 2);
INSERT INTO knowledge_points (id, course_id, parent_id, name, difficulty, sort_order) VALUES (26, 2, 2, 'I/O 系统', 'medium', 6);

INSERT INTO profile_versions (student_id, version, profile_payload, updated_reason)
VALUES (
    'demo',
    1,
    '{"student_id":"demo","major":"计算机科学与技术","grade":"大二","target_courses":["data_structures_algorithms","computer_organization"],"weak_points":["递归调用栈","Cache 映射方式"],"resource_preference":["mindmap","codelab","quiz"],"confidence_score":0.82}',
    '初始化 demo 学习画像'
);
