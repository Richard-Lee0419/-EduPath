CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP NULL
);

CREATE TABLE agent_tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    domain VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    current_agent VARCHAR(128) NULL,
    operation_type VARCHAR(128) NULL,
    request_payload TEXT NULL,
    result_payload TEXT NULL,
    error_message TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_task_steps (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    step_order INT NOT NULL,
    agent VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    message TEXT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_task_steps_task FOREIGN KEY (task_id) REFERENCES agent_tasks(task_id)
);

CREATE TABLE resources (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    course_id BIGINT NOT NULL,
    knowledge_points TEXT NULL,
    difficulty VARCHAR(32) NOT NULL,
    summary TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'published',
    content_format VARCHAR(32) NOT NULL DEFAULT 'markdown',
    content TEXT NULL,
    evidence TEXT NULL,
    safety TEXT NULL,
    object_key VARCHAR(512) NULL,
    object_url VARCHAR(1024) NULL,
    storage_status VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_documents (
    document_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NULL,
    size BIGINT NOT NULL DEFAULT 0,
    parse_status VARCHAR(64) NOT NULL,
    index_status VARCHAR(64) NOT NULL DEFAULT 'not_indexed',
    object_key VARCHAR(512) NULL,
    object_url VARCHAR(1024) NULL,
    storage_status VARCHAR(64) NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL
);

CREATE TABLE quizzes (
    quiz_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    course_id BIGINT NOT NULL,
    difficulty VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE quiz_questions (
    question_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    quiz_id BIGINT NOT NULL,
    question_order INT NOT NULL,
    question_type VARCHAR(64) NOT NULL,
    difficulty VARCHAR(32) NOT NULL,
    knowledge_point VARCHAR(255) NOT NULL,
    question TEXT NOT NULL,
    options TEXT NOT NULL,
    answer VARCHAR(255) NOT NULL,
    explanation TEXT NULL,
    CONSTRAINT fk_quiz_questions_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id)
);

CREATE TABLE quiz_attempts (
    attempt_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    quiz_id BIGINT NOT NULL,
    student_id VARCHAR(64) NOT NULL,
    answers TEXT NOT NULL,
    score INT NOT NULL,
    weak_points TEXT NULL,
    mistake_patterns TEXT NULL,
    recommendation TEXT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_quiz_attempts_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(quiz_id)
);

CREATE TABLE profile_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    profile_payload TEXT NOT NULL,
    source_task_id VARCHAR(64) NULL,
    updated_reason TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    trace_id VARCHAR(128) NULL,
    actor VARCHAR(64) NULL,
    method VARCHAR(16) NULL,
    path VARCHAR(512) NULL,
    status INT NULL,
    duration_ms BIGINT NULL,
    message TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_tasks_status ON agent_tasks(status);
CREATE INDEX idx_resources_course_type ON resources(course_id, resource_type);
CREATE INDEX idx_resources_status ON resources(status);
CREATE INDEX idx_knowledge_documents_course ON knowledge_documents(course_id);
CREATE INDEX idx_profile_versions_student ON profile_versions(student_id, version);

INSERT INTO users (id, username, password_hash, role, status)
VALUES (1, 'demo', 'pbkdf2_sha256$120000$ZWR1cGF0aC1kZW1vLXNhbHQ=$6X/Uzpqu/NubCgGNU+7UdxDNx/53WwSpBakz8NFJS7w=', 'student', 'active');

INSERT INTO users (id, username, password_hash, role, status)
VALUES (2, 'teacher', 'pbkdf2_sha256$120000$ZWR1cGF0aC1kZW1vLXNhbHQ=$6X/Uzpqu/NubCgGNU+7UdxDNx/53WwSpBakz8NFJS7w=', 'teacher', 'active');

INSERT INTO users (id, username, password_hash, role, status)
VALUES (3, 'admin', 'pbkdf2_sha256$120000$ZWR1cGF0aC1kZW1vLXNhbHQ=$6X/Uzpqu/NubCgGNU+7UdxDNx/53WwSpBakz8NFJS7w=', 'admin', 'active');

INSERT INTO resources (
    id, resource_id, title, resource_type, course_id, knowledge_points, difficulty, summary,
    status, content_format, content, evidence, safety, object_key, storage_status
) VALUES (
    1,
    'res_001',
    '二叉树递归遍历个性化讲义',
    'lecture',
    1,
    '["二叉树递归遍历","递归调用栈"]',
    'basic',
    '用图解、调用栈和代码模板理解二叉树前序、中序、后序遍历。',
    'published',
    'markdown',
    '## 二叉树递归遍历\n\nProfileAgent 读取薄弱点，KnowledgeAgent 检索二叉树证据，LectureAgent 生成讲义，SafetyAgent 完成风险检查。',
    '[{"chunk_id":"chunk_1_1","title":"二叉树递归遍历 核心概念","content":"围绕二叉树递归遍历的教材片段、课堂讲义和例题说明。","score":0.9,"source":"data_structures_algorithms/demo-rag-1.md"}]',
    '{"passed":true,"risk_level":"low","issues":[],"suggestions":["继续保留 RAG 证据引用。"],"confidence":0.91}',
    'edupath/resources/res_001.md',
    'seeded_demo'
);

INSERT INTO resources (
    id, resource_id, title, resource_type, course_id, knowledge_points, difficulty, summary,
    status, content_format, content, evidence, safety, object_key, storage_status
) VALUES (
    2,
    'res_002',
    'Cache 映射方式流程图',
    'flowchart',
    2,
    '["Cache 映射方式","存储系统"]',
    'basic',
    '对比直接映射、全相联和组相联，帮助定位地址拆分与冲突缺失。',
    'published',
    'markdown',
    '## Cache 映射方式\n\n直接映射、全相联、组相联分别对应不同地址映射规则。',
    '[{"chunk_id":"chunk_2_1","title":"Cache 映射方式 核心概念","content":"围绕 Cache 映射方式的教材片段、课堂讲义和例题说明。","score":0.9,"source":"computer_organization/demo-rag-1.md"}]',
    '{"passed":true,"risk_level":"low","issues":[],"suggestions":["继续保留 RAG 证据引用。"],"confidence":0.91}',
    'edupath/resources/res_002.md',
    'seeded_demo'
);

INSERT INTO profile_versions (student_id, version, profile_payload, updated_reason)
VALUES (
    'demo_student',
    1,
    '{"student_id":"demo_student","major":"计算机科学与技术","grade":"大二","target_courses":["data_structures_algorithms","computer_organization"],"weak_points":["递归调用栈","Cache 映射方式"],"resource_preference":["mindmap","codelab","quiz"],"confidence_score":0.82}',
    '初始化 demo 学习画像'
);
