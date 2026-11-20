package com.edupath.tutor;

import com.edupath.common.ApiResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tutor/personal-pdfs")
public class PersonalPdfController {

    private final PersonalPdfService materials;

    public PersonalPdfController(PersonalPdfService materials) {
        this.materials = materials;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PersonalPdfRepository.Document>>> list() {
        return privateResponse(materials.list());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PersonalPdfRepository.Document>> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        return privateResponse(materials.upload(file));
    }

    @PostMapping("/{id}/ask")
    public ResponseEntity<ApiResponse<PersonalPdfService.PdfAnswer>> ask(@PathVariable String id, @RequestBody Question body) {
        return privateResponse(materials.ask(id, body.question()));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id) {
        PersonalPdfRepository.PdfContent pdf = materials.content(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(pdf.filename(), StandardCharsets.UTF_8).build().toString())
                .body(pdf.bytes());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> delete(@PathVariable String id) {
        materials.delete(id);
        return privateResponse(Map.of("deleted", true));
    }

    private static <T> ResponseEntity<ApiResponse<T>> privateResponse(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }

    public record Question(String question) {}
}
