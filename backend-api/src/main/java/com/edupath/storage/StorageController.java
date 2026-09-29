package com.edupath.storage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage")
@Tag(name = "对象存储")
public class StorageController {

    private final ObjectStorageService objectStorageService;

    public StorageController(ObjectStorageService objectStorageService) {
        this.objectStorageService = objectStorageService;
    }

    @GetMapping("/signed")
    @Operation(summary = "本地对象签名下载")
    public ResponseEntity<ByteArrayResource> signedDownload(
            @RequestParam("key") String objectKey,
            @RequestParam long expires,
            @RequestParam String signature) {
        ObjectStorageService.StoredObjectContent object =
                objectStorageService.readSignedLocalObject(objectKey, expires, signature);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(object.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(object.filename()).build().toString())
                .body(new ByteArrayResource(object.content()));
    }
}
