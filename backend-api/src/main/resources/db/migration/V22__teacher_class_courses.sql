ALTER TABLE courses ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'public';
ALTER TABLE knowledge_documents ADD COLUMN knowledge_point_id BIGINT NULL;
ALTER TABLE knowledge_documents ADD COLUMN index_error VARCHAR(300) NULL;

CREATE INDEX idx_courses_visibility_status ON courses (visibility, status);
CREATE INDEX idx_knowledge_documents_topic ON knowledge_documents (course_id, knowledge_point_id);
