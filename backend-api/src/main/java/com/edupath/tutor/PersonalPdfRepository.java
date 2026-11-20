package com.edupath.tutor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
class PersonalPdfRepository {

    private static final int MAX_DOCUMENTS = 10;
    private final JdbcTemplate jdbc;

    PersonalPdfRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Document> list(long owner) {
        return jdbc.query("""
                SELECT id, filename, page_count, uploaded_at FROM personal_tutor_pdfs
                WHERE owner_user_id = ? ORDER BY uploaded_at DESC, id
                """, PersonalPdfRepository::document, owner);
    }

    @Transactional
    public Document insert(long owner, String id, String filename, int pageCount, String pages, byte[] pdf) {
        // Serialize the quota check with other uploads for the same student.
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, owner);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM personal_tutor_pdfs WHERE owner_user_id = ?", Long.class, owner)
                >= MAX_DOCUMENTS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "最多保留 10 份 PDF，请删除旧资料后重试");
        }
        jdbc.update("""
                INSERT INTO personal_tutor_pdfs (id, owner_user_id, filename, page_count, page_text, pdf_bytes)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, owner, filename, pageCount, pages, pdf);
        return jdbc.queryForObject("""
                SELECT id, filename, page_count, uploaded_at FROM personal_tutor_pdfs WHERE id = ? AND owner_user_id = ?
                """, PersonalPdfRepository::document, id, owner);
    }

    Optional<String> findPages(long owner, String id) {
        return jdbc.query("SELECT page_text FROM personal_tutor_pdfs WHERE owner_user_id = ? AND id = ?",
                (row, ignored) -> row.getString("page_text"), owner, id).stream().findFirst();
    }

    Optional<PdfContent> findContent(long owner, String id) {
        return jdbc.query("SELECT filename, pdf_bytes FROM personal_tutor_pdfs WHERE owner_user_id = ? AND id = ?",
                (row, ignored) -> new PdfContent(row.getString("filename"), row.getBytes("pdf_bytes")), owner, id)
                .stream().findFirst();
    }

    boolean exists(long owner, String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM personal_tutor_pdfs WHERE owner_user_id = ? AND id = ?)
                """, Boolean.class, owner, id));
    }

    boolean delete(long owner, String id) {
        return jdbc.update("DELETE FROM personal_tutor_pdfs WHERE owner_user_id = ? AND id = ?", owner, id) != 0;
    }

    private static Document document(ResultSet row, int ignored) throws SQLException {
        return new Document(row.getString("id"), row.getString("filename"), row.getInt("page_count"),
                row.getTimestamp("uploaded_at").toInstant());
    }

    record Document(String id, String filename, int pageCount, Instant uploadedAt) {}

    record PdfContent(String filename, byte[] bytes) {}
}
