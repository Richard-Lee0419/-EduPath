package com.edupath.kb;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeDocumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(
            long courseId,
            String filename,
            String contentType,
            long size,
            String parseStatus,
            String objectKey,
            String objectUrl,
            String storageStatus) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_documents (
                    course_id, filename, content_type, size, parse_status, index_status,
                    object_key, object_url, storage_status, uploaded_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'not_indexed', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                courseId,
                filename,
                contentType,
                size,
                parseStatus,
                objectKey,
                objectUrl,
                storageStatus);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(document_id) FROM knowledge_documents", Long.class);
        return id == null ? 0 : id;
    }

    public List<DocumentRow> list(Long courseId, String status) {
        if (courseId != null && status != null && !status.isBlank()) {
            return jdbcTemplate.query(
                    """
                    SELECT * FROM knowledge_documents
                    WHERE deleted_at IS NULL AND course_id = ? AND parse_status = ?
                    ORDER BY uploaded_at DESC
                    """,
                    this::mapRow,
                    courseId,
                    status);
        }
        if (courseId != null) {
            return jdbcTemplate.query(
                    """
                    SELECT * FROM knowledge_documents
                    WHERE deleted_at IS NULL AND course_id = ?
                    ORDER BY uploaded_at DESC
                    """,
                    this::mapRow,
                    courseId);
        }
        return jdbcTemplate.query(
                """
                SELECT * FROM knowledge_documents
                WHERE deleted_at IS NULL
                ORDER BY uploaded_at DESC
                """,
                this::mapRow);
    }

    public Optional<DocumentRow> find(long documentId) {
        return jdbcTemplate
                .query(
                        "SELECT * FROM knowledge_documents WHERE document_id = ? AND deleted_at IS NULL",
                        this::mapRow,
                        documentId)
                .stream()
                .findFirst();
    }

    public int updateStatus(long documentId, String parseStatus, String indexStatus) {
        return jdbcTemplate.update(
                """
                UPDATE knowledge_documents
                SET parse_status = ?, index_status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND deleted_at IS NULL
                """,
                parseStatus,
                indexStatus,
                documentId);
    }

    public int markDeleted(long documentId) {
        return jdbcTemplate.update(
                """
                UPDATE knowledge_documents
                SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND deleted_at IS NULL
                """,
                documentId);
    }

    public void createIndexJob(long documentId, String taskId, String status, String requestedBy, String message) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_index_jobs (document_id, task_id, status, requested_by, message, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                documentId,
                taskId,
                status,
                requestedBy,
                message);
    }

    public void finishIndexJob(long documentId, String taskId, String status, String message) {
        jdbcTemplate.update(
                """
                UPDATE knowledge_index_jobs
                SET status = ?, message = ?, finished_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND task_id = ?
                """,
                status,
                message,
                documentId,
                taskId);
    }

    public void createChunk(
            long documentId,
            String chunkId,
            String title,
            String content,
            String source,
            double score) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_document_chunks (document_id, chunk_id, title, content, source, score, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'active', CURRENT_TIMESTAMP)
                """,
                documentId,
                chunkId,
                title,
                content == null ? "" : content,
                source,
                score);
    }

    public void deleteChunks(long documentId) {
        jdbcTemplate.update("DELETE FROM knowledge_document_chunks WHERE document_id = ?", documentId);
    }

    public List<ChunkRow> chunks(long documentId) {
        return jdbcTemplate.query(
                """
                SELECT chunk_id, title, content, source, score
                FROM knowledge_document_chunks
                WHERE document_id = ? AND status = 'active'
                ORDER BY id ASC
                """,
                (rs, rowNum) -> new ChunkRow(
                        rs.getString("chunk_id"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("source"),
                        rs.getDouble("score")),
                documentId);
    }

    private DocumentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentRow(
                rs.getLong("document_id"),
                rs.getLong("course_id"),
                rs.getString("filename"),
                rs.getString("content_type"),
                rs.getLong("size"),
                rs.getString("parse_status"),
                rs.getString("index_status"),
                rs.getString("object_key"),
                rs.getString("object_url"),
                rs.getString("storage_status"),
                toOffsetDateTime(rs.getTimestamp("uploaded_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    public record DocumentRow(
            long documentId,
            long courseId,
            String filename,
            String contentType,
            long size,
            String parseStatus,
            String indexStatus,
            String objectKey,
            String objectUrl,
            String storageStatus,
            OffsetDateTime uploadedAt,
            OffsetDateTime updatedAt) {}

    public record ChunkRow(String chunkId, String title, String content, String source, double score) {}
}
