package com.edupath.kb;

import com.edupath.ai.AiAgentClient;
import com.edupath.ai.AiAgentClient.KnowledgeChunk;
import com.edupath.ai.AiAgentClient.KnowledgeDocumentPayload;
import com.edupath.ai.AiAgentClient.KnowledgeIngestResult;
import com.edupath.auth.AuthContext;
import com.edupath.common.ExternalServiceException;
import com.edupath.course.CourseAccess;
import com.edupath.course.CourseCatalogService;
import com.edupath.course.CourseRepository;
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
    private final CourseRepository courses;
    private final CourseAccess courseAccess;
    private final AiAgentClient aiAgentClient;
    private final ObjectStorageService objectStorageService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final long maxUploadBytes;
    private static final long CLASS_PDF_MAX_BYTES = 8 * 1024 * 1024;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "markdown", "pdf", "docx", "pptx");
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    public KnowledgeBaseService(
            CourseCatalogService courseCatalogService,
            CourseRepository courses,
            CourseAccess courseAccess,
            AiAgentClient aiAgentClient,
            ObjectStorageService objectStorageService,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            @Value("${edupath.kb.max-upload-bytes:20971520}") long maxUploadBytes) {
        this.courseCatalogService = courseCatalogService;
        this.courses = courses;
        this.courseAccess = courseAccess;
        this.aiAgentClient = aiAgentClient;
        this.objectStorageService = objectStorageService;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.maxUploadBytes = Math.max(1, maxUploadBytes);
    }

    @Transactional(noRollbackFor = ExternalServiceException.class)
    public DocumentUploadResult upload(long courseId, String filename, String contentType, byte[] content,
                                       Long knowledgePointId) {
        byte[] safeContent = content == null ? new byte[0] : content;
        boolean classCourse = courseAccess.isClassCourse(courseId);
        String topic = null;
        if (classCourse) {
            courses.lockClassCourse(courseId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "班级课程不存在"));
            requireManager(courseId);
            filename = validateClassPdf(filename, safeContent);
            contentType = "application/pdf";
            topic = topicName(courseId, knowledgePointId);
        }
        validateUpload(filename, contentType, safeContent.length);
        StoredObject storedObject = objectStorageService.store(classCourse ? "class-course-pdfs" : "knowledge-base",
                filename, contentType, safeContent);
        long documentId = knowledgeDocumentRepository.insert(
                courseId,
                filename == null || filename.isBlank() ? "untitled-document" : filename,
                contentType,
                safeContent.length,
                "pending",
                storedObject.objectKey(),
                classCourse ? null : storedObject.objectUrl(),
                storedObject.storageStatus(),
                classCourse ? knowledgePointId : null);
        long jobId = knowledgeDocumentRepository.beginIndexJob(documentId, null, AuthContext.currentUsername());
        IngestOutcome outcome;
        try {
            outcome = ingestDocument(documentId, courseId, filename, contentType, safeContent, storedObject.objectKey(),
                    classCourse, knowledgePointId, topic);
        } catch (ExternalServiceException exception) {
            knowledgeDocumentRepository.updateIndexState(documentId, "failed", "failed", failureMessage(exception));
            knowledgeDocumentRepository.finishIndexAttempt(jobId, null, "failed", failureMessage(exception));
            throw exception;
        }
        knowledgeDocumentRepository.finishIndexAttempt(jobId, outcome.taskId(), outcome.jobStatus(), outcome.reason());
        return new DocumentUploadResult(
                documentId,
                outcome.parseStatus(),
                outcome.indexStatus(),
                storedObject.provider(),
                storedObject.bucket(),
                classCourse ? null : storedObject.objectKey(),
                classCourse ? null : storedObject.objectUrl(),
                storedObject.storageStatus(),
                classCourse ? null : signedUrl(storedObject.objectKey()),
                outcome.safetyStatus(),
                outcome.reason());
    }

    public KnowledgeSearchResponse search(KnowledgeSearchRequest request) {
        if (request == null) {
            request = new KnowledgeSearchRequest(1, courseCatalogService.normalizeCourseTopic(1), 5);
        }
        int courseId = request.resolvedCourseId();
        courseAccess.requireReadable(courseId);
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
        if (courseAccess.isClassCourse(courseId)) {
            var readableDocuments = knowledgeDocumentRepository.list((long) courseId, null).stream()
                    .filter(KnowledgeDocumentRepository.DocumentRow::indexed)
                    .map(KnowledgeDocumentRepository.DocumentRow::documentId).collect(java.util.stream.Collectors.toSet());
            results = results.stream().filter(item -> item.courseId() == courseId
                    && item.documentId() != null && readableDocuments.contains(item.documentId())).toList();
            courseAccess.requireReadable(courseId);
        }
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
                stringList(rawMap.get("heading_path")),
                rawMap.get("document_id") instanceof Number id ? id.longValue() : null,
                rawMap.get("page") instanceof Number page ? page.intValue() : null);
    }

    private static String validateClassPdf(String filename, byte[] content) {
        if (content.length > CLASS_PDF_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "课程 PDF 不能超过 8 MiB");
        }
        String name = filename == null ? "" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
        byte[] header = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (name.length() <= 4 || name.length() > 255 || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                || content.length < header.length || java.util.Arrays.mismatch(content, 0, header.length,
                        header, 0, header.length) != -1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择有效的课程 PDF 文件");
        }
        return name;
    }

    private String topicName(long courseId, Long topicId) {
        if (topicId == null) return null;
        return courses.listKnowledgePoints(courseId).stream().filter(point -> point.id() == topicId)
                .map(CourseRepository.KnowledgePointRow::name).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识点不属于这门课程"));
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
        if (courseId != null) courseAccess.requireReadable(courseId);
        return knowledgeDocumentRepository.list(courseId, status).stream()
                .map(this::visibleDocument).filter(java.util.Objects::nonNull).toList();
    }

    private KnowledgeDocumentRepository.DocumentRow visibleDocument(KnowledgeDocumentRepository.DocumentRow document) {
        long courseId = document.courseId();
        if (!courseAccess.isClassCourse(courseId)) return document;
        if (courseAccess.canManage(courseId)) return document.protectedMetadata(true);
        return document.indexed() && courseAccess.isReadable(courseId) ? document.protectedMetadata(false) : null;
    }

    @Transactional
    public KnowledgeDocumentRepository.DocumentRow updateDocumentStatus(long documentId, DocumentStatusUpdateRequest request) {
        String parseStatus = request == null || request.parseStatus() == null || request.parseStatus().isBlank()
                ? "pending"
                : request.parseStatus();
        String indexStatus = request == null || request.indexStatus() == null || request.indexStatus().isBlank()
                ? "not_indexed"
                : request.indexStatus();
        var document = knowledgeDocumentRepository.find(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + documentId));
        if (!parseStatus.equals(document.parseStatus()) || !indexStatus.equals(document.indexStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "解析和索引状态由实际摄入任务决定，请执行重建索引");
        }
        int updated = knowledgeDocumentRepository.updateStatus(documentId, parseStatus, indexStatus);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + documentId);
        }
        return visibleDocument(knowledgeDocumentRepository.find(documentId).orElseThrow());
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

    @Transactional(noRollbackFor = ExternalServiceException.class)
    public DocumentUploadResult reindexDocument(long documentId) {
        KnowledgeDocumentRepository.DocumentRow document = knowledgeDocumentRepository
                .lock(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + documentId));
        requireManager(document.courseId());
        boolean classCourse = courseAccess.isClassCourse(document.courseId());
        if (classCourse) {
            // Keep the current index usable if vector removal fails. A later parse must not serve stale excerpts.
            aiAgentClient.deleteKnowledgeDocumentVectors(documentId, document.courseId());
        }
        long jobId = knowledgeDocumentRepository.beginIndexJob(documentId, null, AuthContext.currentUsername());
        IngestOutcome outcome;
        try {
            byte[] content = objectStorageService.read(document.objectKey());
            outcome = ingestDocument(
                document.documentId(),
                document.courseId(),
                document.filename(),
                document.contentType(),
                content,
                document.objectKey(),
                classCourse,
                document.knowledgePointId(),
                classCourse ? topicName(document.courseId(), document.knowledgePointId()) : null);
        } catch (RuntimeException exception) {
            knowledgeDocumentRepository.deleteChunks(documentId);
            knowledgeDocumentRepository.updateIndexState(documentId, "failed", "failed", failureMessage(exception));
            knowledgeDocumentRepository.finishIndexAttempt(jobId, null, "failed", failureMessage(exception));
            throw new ExternalServiceException("AI 服务", "文档重建索引失败", exception);
        }
        knowledgeDocumentRepository.finishIndexAttempt(jobId, outcome.taskId(), outcome.jobStatus(), outcome.reason());
        if (!"succeeded".equals(outcome.jobStatus())) {
            throw new ExternalServiceException("AI 服务", outcome.reason());
        }
        return new DocumentUploadResult(
                document.documentId(),
                outcome.parseStatus(),
                outcome.indexStatus(),
                objectStorageService.provider(),
                objectStorageService.bucket(),
                classCourse ? null : document.objectKey(),
                classCourse ? null : document.objectUrl(),
                document.storageStatus(),
                classCourse ? null : signedUrl(document.objectKey()),
                outcome.safetyStatus(),
                outcome.reason());
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
        if (courseAccess.isClassCourse(document.courseId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "班级资料须在登录后打开原文");
        }
        SignedUrl signed = objectStorageService.signedDownloadUrl(document.objectKey());
        return new DocumentDownloadUrl(document.documentId(), signed.url(), signed.expiresAt());
    }

    public CoursePdf openCoursePdf(long documentId) {
        var document = knowledgeDocumentRepository.find(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF 资料不存在"));
        if (!courseAccess.isClassCourse(document.courseId()) || visibleDocument(document) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF 资料不存在");
        }
        return new CoursePdf(document.filename(), objectStorageService.read(document.objectKey()));
    }

    private void requireManager(long courseId) {
        if (!courseAccess.canManage(courseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无权管理该课程");
        }
    }

    private SignedUrl signedUrl(String objectKey) {
        return objectStorageService.signedDownloadUrl(objectKey);
    }

    private IngestOutcome ingestDocument(
            long documentId,
            long courseId,
            String filename,
            String contentType,
            byte[] content,
            String objectKey,
            boolean classCourse,
            Long knowledgePointId,
            String topic) {
        KnowledgeIngestResult result = aiAgentClient.ingestKnowledgeDocument(new KnowledgeDocumentPayload(
                documentId,
                (int) courseId,
                filename == null || filename.isBlank() ? "untitled-document" : filename,
                contentType,
                content == null ? new byte[0] : content,
                objectKey, classCourse ? knowledgePointId : null, topic, classCourse));
        if (result == null) {
            throw new ExternalServiceException("AI 服务", "知识库摄入未返回结果");
        }
        String parseStatus = clean(result.parseStatus()).isEmpty() ? "parsed" : clean(result.parseStatus());
        boolean safetyPassed = result.safety() != null && result.safety().passed()
                && "passed".equalsIgnoreCase(result.safetyStatus());
        String reason = clean(result.indexError());
        if (!safetyPassed || !"indexed".equalsIgnoreCase(result.indexStatus()) || !reason.isEmpty()) {
            if (!safetyPassed && !"not_reviewed".equals(result.safetyStatus())) {
                List<String> issues = result.safety() == null || result.safety().issues() == null
                        ? List.of() : result.safety().issues();
                String rejection = "SafetyAgent 审查未通过，未写入后端 chunk"
                        + (issues.isEmpty() ? "（审查结果缺失或矛盾）" : "：" + String.join("；", issues));
                reason = reason.isEmpty() ? rejection : rejection + "；" + reason;
            } else if (reason.isEmpty()) {
                reason = "向量索引未完成，未写入后端 chunk";
            }
            knowledgeDocumentRepository.deleteChunks(documentId);
            knowledgeDocumentRepository.updateIndexState(documentId, parseStatus, "failed", truncate(reason));
            return new IngestOutcome(result.taskId(), parseStatus, "failed", result.safetyStatus(),
                    safetyPassed ? "failed" : "rejected", truncate(reason));
        }
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
        knowledgeDocumentRepository.updateIndexState(documentId, parseStatus, "indexed", null);
        return new IngestOutcome(result.taskId(), parseStatus, "indexed", result.safetyStatus(), "succeeded", "");
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value) {
        return value.substring(0, Math.min(300, value.length()));
    }

    private String failureMessage(RuntimeException exception) {
        return truncate(exception.getMessage() == null ? "解析或索引服务失败" : exception.getMessage());
    }

    private record IngestOutcome(String taskId, String parseStatus, String indexStatus,
            String safetyStatus, String jobStatus, String reason) {}

    @Transactional
    public void deleteDocument(long documentId) {
        KnowledgeDocumentRepository.DocumentRow document = knowledgeDocumentRepository
                .lock(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + documentId));
        aiAgentClient.deleteKnowledgeDocumentVectors(document.documentId(), document.courseId());
        knowledgeDocumentRepository.deleteChunks(documentId);
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
            List<String> headingPath,
            Long documentId,
            Integer page) {}

    public record DocumentUploadResult(
            long documentId,
            String parseStatus,
            String indexStatus,
            String storageProvider,
            String bucket,
            String objectKey,
            String objectUrl,
            String storageStatus,
            SignedUrl signedDownloadUrl,
            String safetyStatus,
            String indexError) {}

    public record DocumentDownloadUrl(long documentId, String url, java.time.OffsetDateTime expiresAt) {}

    public record CoursePdf(String filename, byte[] content) {}

    public record DocumentStatusUpdateRequest(String parseStatus, String indexStatus) {}
}
