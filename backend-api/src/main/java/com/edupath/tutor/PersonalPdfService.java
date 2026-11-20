package com.edupath.tutor;

import com.edupath.ai.AiAgentClient;
import com.edupath.auth.AuthContext;
import com.edupath.auth.RoleGuard;
import com.edupath.common.ExternalServiceException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonalPdfService {

    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_QUESTION_LENGTH = 500;
    private final PersonalPdfRepository documents;
    private final AiAgentClient ai;
    private final RoleGuard roles;
    private final ObjectMapper mapper;

    PersonalPdfService(PersonalPdfRepository documents, AiAgentClient ai, RoleGuard roles, ObjectMapper mapper) {
        this.documents = documents;
        this.ai = ai;
        this.roles = roles;
        this.mapper = mapper;
    }

    List<PersonalPdfRepository.Document> list() {
        return documents.list(studentId());
    }

    PersonalPdfRepository.Document upload(MultipartFile upload) throws IOException {
        long owner = studentId();
        if (upload.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF 文件不能超过 8 MiB");
        }
        String filename = safeFilename(upload.getOriginalFilename());
        byte[] raw = upload.getBytes();
        PdfIndex index = ai.indexPersonalPdf(raw);
        if (index.error() != null) {
            String message = switch (index.error()) {
                case "encrypted" -> "请先解除 PDF 加密，再上传可读取的文件";
                case "page_limit" -> "PDF 最多支持 100 页，请拆分后上传";
                case "text_limit" -> "PDF 文本量超过处理上限，请拆分后上传";
                case "no_text" -> "PDF 没有可提取的文本，请上传文本版或识别后的 PDF";
                default -> "PDF 无法解析，请换一份可正常打开的 PDF";
            };
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
        }
        if (index.pageCount() < 1 || index.pages() == null || index.pages().isEmpty()) {
            throw new ExternalServiceException("个人 PDF", "解析结果缺少有效页");
        }
        return save(owner, filename, index, raw);
    }

    PdfAnswer ask(String id, String question) {
        long owner = studentId();
        String text = question == null ? "" : question.strip();
        int length = text.codePointCount(0, text.length());
        if (length < 2 || length > MAX_QUESTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 2–500 字的问题");
        }
        String source = documents.findPages(owner, id).orElseThrow(PersonalPdfService::missing);
        PdfAnswer answer = ai.askPersonalPdf(text, readPages(source));
        if (!documents.exists(owner, id)) {
            throw missing();
        }
        return answer;
    }

    PersonalPdfRepository.PdfContent content(String id) {
        return documents.findContent(studentId(), id).orElseThrow(PersonalPdfService::missing);
    }

    void delete(String id) {
        if (!documents.delete(studentId(), id)) {
            throw missing();
        }
    }

    private long studentId() {
        roles.requireAny("student");
        return AuthContext.requirePrincipal().userId();
    }

    private PersonalPdfRepository.Document save(long owner, String filename, PdfIndex index, byte[] original) {
        try {
            String pages = mapper.writeValueAsString(index.pages());
            return documents.insert(owner, UUID.randomUUID().toString(), filename, index.pageCount(), pages, original);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存 PDF 页文本", exception);
        }
    }

    private List<PdfPage> readPages(String source) {
        try {
            return mapper.readValue(source, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取 PDF 页文本", exception);
        }
    }

    private static String safeFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
        if (name.length() > 255 || name.length() <= 4 || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择扩展名为 .pdf 的有效文件");
        }
        return name;
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "个人资料不存在或已删除");
    }

    public record PdfPage(int number, String text) {}

    public record PdfIndex(@JsonProperty("page_count") int pageCount, List<PdfPage> pages, String error) {}

    public record PdfEvidence(int page, String excerpt) {}

    public record PdfAnswer(String outcome, String guidance, List<PdfEvidence> evidence) {}
}
