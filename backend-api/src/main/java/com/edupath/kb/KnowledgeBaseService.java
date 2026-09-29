package com.edupath.kb;

import com.edupath.ai.AiAgentClient;
import com.edupath.ai.AiAgentClient.KnowledgeChunk;
import com.edupath.ai.AiAgentClient.KnowledgeDocumentPayload;
import com.edupath.ai.AiAgentClient.KnowledgeIngestResult;
import com.edupath.auth.AuthContext;
import com.edupath.common.ExternalServiceException;
import com.edupath.course.CourseCatalogService;
import com.edupath.storage.ObjectStorageService;
import com.edupath.storage.ObjectStorageService.SignedUrl;
import com.edupath.storage.ObjectStorageService.StoredObject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeBaseService {

    private final CourseCatalogService courseCatalogService;
    private final AiAgentClient aiAgentClient;
    private final ObjectStorageService objectStorageService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final long maxUploadBytes;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "markdown", "pdf", "docx", "pptx");
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    public KnowledgeBaseService(
            CourseCatalogService courseCatalogService,
            AiAgentClient aiAgentClient,
            ObjectStorageService objectStorageService,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            @Value("${edupath.kb.max-upload-bytes:20971520}") long maxUploadBytes) {
        this.courseCatalogService = courseCatalogService;
        this.aiAgentClient = aiAgentClient;
        this.objectStorageService = objectStorageService;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.maxUploadBytes = Math.max(1, maxUploadBytes);
    }

    @Transactional
    public DocumentUploadResult upload(long courseId, String filename, String contentType, byte[] content) {
        byte[] safeContent = content == null ? new byte[0] : content;
        validateUpload(filename, contentType, safeContent.length);
        StoredObject storedObject = objectStorageService.store("knowledge-base", filename, contentType, safeContent);
        long documentId = knowledgeDocumentRepository.insert(
                courseId,
                filename == null || filename.isBlank() ? "untitled-document" : filename,
                contentType,
                safeContent.length,
                "pending",
                storedObject.objectKey(),
                storedObject.objectUrl(),
                storedObject.storageStatus());
        KnowledgeIngestResult ingestResult;
        try {
            ingestResult = ingestDocument(documentId, courseId, filename, contentType, safeContent, storedObject.objectKey());
        } catch (RuntimeException exception) {
            knowledgeDocumentRepository.updateStatus(documentId, "failed", "failed");
            throw exception;
        }
        return new DocumentUploadResult(
                documentId,
                ingestResult.parseStatus(),
                ingestResult.indexStatus(),
                storedObject.provider(),
                storedObject.bucket(),
                storedObject.objectKey(),
                storedObject.objectUrl(),
                storedObject.storageStatus(),
                signedUrl(storedObject.objectKey()));
    }

    public KnowledgeSearchResponse search(KnowledgeSearchRequest request) {
        if (request == null) {
            request = new KnowledgeSearchRequest(1, courseCatalogService.normalizeCourseTopic(1), 5);
        }
        int topK = request.topK() == null ? 5 : Math.max(1, Math.min(request.topK(), 10));
        String query = request.query() == null || request.query().isBlank()
                ? courseCatalogService.normalizeCourseTopic(request.resolvedCourseId())
                : request.query();
        Map<String, Object> aiResponse =
                aiAgentClient.searchKnowledge(new KnowledgeSearchRequest(request.resolvedCourseId(), query, topK));
        Object rawResults = aiResponse.get("results");
        List<KnowledgeSearchResult> results = rawResults instanceof List<?> list
                ? list.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(this::toSearchResult)
                        .toList()
                : List.of();
        return new KnowledgeSearchResponse(
                request.resolvedCourseId(),
                query,
                topK,
                toCorpusSummary(aiResponse.get("corpus")),
                results);
    }

    private KnowledgeSearchResult toSearchResult(Map<?, ?> rawMap) {
        return new KnowledgeSearchResult(
                text(rawMap, "chunk_id", "chunkId"),
                integer(rawMap.get("course_id")),
                integer(rawMap.get("knowledge_point_id")),
                text(rawMap, "knowledge_point", "knowledgePoint"),
                integerList(rawMap.get("related_knowledge_point_ids")),
                text(rawMap, "title"),
                text(rawMap, "content"),
                score(rawMap.get("score")),
                text(rawMap, "source"),
                text(rawMap, "author"),
                text(rawMap, "source_url", "sourceUrl"),
                text(rawMap, "license"),
                text(rawMap, "license_status", "licenseStatus"),
                text(rawMap, "document_type", "documentType"),
                bool(rawMap.get("contains_examples")),
                stringList(rawMap.get("heading_path")));
    }

    private KnowledgeCorpusSummary toCorpusSummary(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return new KnowledgeCorpusSummary("unknown", 0, 0, 0, "", "");
        }
        return new KnowledgeCorpusSummary(
                text(rawMap, "mode"),
                integer(rawMap.get("documents")),
                integer(rawMap.get("external_documents")),
                integer(rawMap.get("chunks")),
                text(rawMap, "vector_store", "vectorStore"),
                text(rawMap, "embedding"));
    }

    private void validateUpload(String filename, String contentType, long contentLength) {
        if (contentLength <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库上传不允许空文件");
        }
        if (contentLength > maxUploadBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超过限制: " + maxUploadBytes + " bytes");
        }
        String extension = extension(filename);
        String mediaType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        boolean hasMetadata = !extension.isBlank() || !mediaType.isBlank();
        boolean supported = SUPPORTED_EXTENSIONS.contains(extension) || SUPPORTED_MEDIA_TYPES.contains(mediaType);
        if (hasMetadata && !supported) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的文件类型，仅支持 txt、md、pdf、docx、pptx");
        }
    }

    private String extension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String normalized = filename.toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        if (dot < 0 || dot == normalized.length() - 1) {
            return "";
        }
        return normalized.substring(dot + 1);
    }

    private String text(Map<?, ?> rawMap, String... keys) {
        for (String key : keys) {
            Object value = rawMap.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private double score(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            return 0.0;
        }
    }

    private int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }

    private List<Integer> integerList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::integer).toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    public List<KnowledgeDocumentRepository.DocumentRow> documents(Long courseId, String status) {
        return knowledgeDocumentRepository.list(courseId, status);
    }

    @Transactional
    public KnowledgeDocumentRepository.DocumentRow updateDocumentStatus(long documentId, DocumentStatusUpdateRequest request) {
        String parseStatus = request == null || request.parseStatus() == null || request.parseStatus().isBlank()
                ? "pending"
                : request.parseStatus();
        String indexStatus = request == null || request.indexStatus() == null || request.indexStatus().isBlank()
                ? "not_indexed"
                : request.indexStatus();
        int updated = knowledgeDocumentRepository.updateStatus(documentId, parseStatus, indexStatus);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + documentId);
        }
        return knowledgeDocumentRepository.find(documentId).orElseThrow();
    }

    @Transactional
    public void createIndexJob(long documentId, String taskId, String status, String message) {
        knowledgeDocumentRepository.createIndexJob(
                documentId,
                taskId,
                status,
                AuthContext.currentUsername(),
                message);
    }

    @Transactional
    public DocumentUploadResult reindexDocument(long documentId) {
        KnowledgeDocumentRepository.DocumentRow document = knowledgeDocumentRepository
                .find(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + documentId));
        byte[] content = objectStorageService.read(document.objectKey());
        KnowledgeIngestResult ingestResult = ingestDocument(
                document.documentId(),
                document.courseId(),
                document.filename(),
                document.contentType(),
                content,
                document.objectKey());
        return new DocumentUploadResult(
                document.documentId(),
                ingestResult.parseStatus(),
                ingestResult.indexStatus(),
                objectStorageService.provider(),
                objectStorageService.bucket(),
                document.objectKey(),
                document.objectUrl(),
                document.storageStatus(),
                signedUrl(document.objectKey()));
    }

    public long documentCourseId(long documentId) {
        return knowledgeDocumentRepository
                .find(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + documentId))
                .courseId();
    }

    public DocumentDownloadUrl downloadUrl(long documentId) {
        KnowledgeDocumentRepository.DocumentRow document = knowledgeDocumentRepository
                .find(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + documentId));
        SignedUrl signed = objectStorageService.signedDownloadUrl(document.objectKey());
        return new DocumentDownloadUrl(document.documentId(), signed.url(), signed.expiresAt());
    }

    private SignedUrl signedUrl(String objectKey) {
        return objectStorageService.signedDownloadUrl(objectKey);
    }

    private KnowledgeIngestResult ingestDocument(
            long documentId,
            long courseId,
            String filename,
            String contentType,
            byte[] content,
            String objectKey) {
        KnowledgeIngestResult result = aiAgentClient.ingestKnowledgeDocument(new KnowledgeDocumentPayload(
                documentId,
                (int) courseId,
                filename == null || filename.isBlank() ? "untitled-document" : filename,
                contentType,
                content == null ? new byte[0] : content,
                objectKey));
        if (result.chunks() == null || result.chunks().isEmpty()) {
            throw new ExternalServiceException("AI 服务", "知识库解析未返回有效 chunk");
        }
        knowledgeDocumentRepository.deleteChunks(documentId);
        for (KnowledgeChunk chunk : result.chunks()) {
            knowledgeDocumentRepository.createChunk(
                    documentId,
                    chunk.chunkId(),
                    chunk.title() == null || chunk.title().isBlank() ? "文档片段" : chunk.title(),
                    chunk.content(),
                    chunk.source(),
                    chunk.score());
        }
        knowledgeDocumentRepository.updateStatus(
                documentId,
                result.parseStatus() == null || result.parseStatus().isBlank() ? "parsed" : result.parseStatus(),
                result.indexStatus() == null || result.indexStatus().isBlank() ? "indexed" : result.indexStatus());
        return result;
    }

    @Transactional
    public void deleteDocument(long documentId) {
        KnowledgeDocumentRepository.DocumentRow document = knowledgeDocumentRepository
                .find(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + documentId));
        int updated = knowledgeDocumentRepository.markDeleted(documentId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + documentId);
        }
        objectStorageService.delete(document.objectKey());
    }

    public record KnowledgeSearchRequest(Integer courseId, String query, Integer topK) {
        public int resolvedCourseId() {
            return courseId == null ? 1 : courseId;
        }
    }

    public record KnowledgeSearchResponse(
            int courseId,
            String query,
            int topK,
            KnowledgeCorpusSummary corpus,
            List<KnowledgeSearchResult> results) {}

    public record KnowledgeCorpusSummary(
            String mode,
            int documents,
            int externalDocuments,
            int chunks,
            String vectorStore,
            String embedding) {}

    public record KnowledgeSearchResult(
            String chunkId,
            int courseId,
            int knowledgePointId,
            String knowledgePoint,
            List<Integer> relatedKnowledgePointIds,
            String title,
            String content,
            double score,
            String source,
            String author,
            String sourceUrl,
            String license,
            String licenseStatus,
            String documentType,
            boolean containsExamples,
            List<String> headingPath) {}

    public record DocumentUploadResult(
            long documentId,
            String parseStatus,
            String indexStatus,
            String storageProvider,
            String bucket,
            String objectKey,
            String objectUrl,
            String storageStatus,
            SignedUrl signedDownloadUrl) {}

    public record DocumentDownloadUrl(long documentId, String url, java.time.OffsetDateTime expiresAt) {}

    public record DocumentStatusUpdateRequest(String parseStatus, String indexStatus) {}
}
