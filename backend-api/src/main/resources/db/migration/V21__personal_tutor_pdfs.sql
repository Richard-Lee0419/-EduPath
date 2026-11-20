CREATE TABLE personal_tutor_pdfs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    page_count INT NOT NULL,
    page_text LONGTEXT NOT NULL,
    pdf_bytes LONGBLOB NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_personal_pdf_owner FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_personal_pdf_owner ON personal_tutor_pdfs (owner_user_id, uploaded_at);
