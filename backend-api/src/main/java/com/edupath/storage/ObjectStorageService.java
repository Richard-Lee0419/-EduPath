package com.edupath.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ObjectStorageService {

    private final String provider;
    private final String bucket;
    private final String endpoint;
    private final String keyPrefix;
    private final Path localRoot;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String signingSecret;
    private final int lifecycleDays;

    public ObjectStorageService(
            @Value("${edupath.storage.provider:local}") String provider,
            @Value("${edupath.storage.bucket:edupath-demo}") String bucket,
            @Value("${edupath.storage.endpoint:}") String endpoint,
            @Value("${edupath.storage.key-prefix:edupath}") String keyPrefix,
            @Value("${edupath.storage.local-root:storage}") String localRoot,
            @Value("${edupath.storage.access-key-id:}") String accessKeyId,
            @Value("${edupath.storage.access-key-secret:}") String accessKeySecret,
            @Value("${edupath.storage.signing-secret:dev-edupath-storage-signing-secret}") String signingSecret,
            @Value("${edupath.storage.lifecycle-days:30}") int lifecycleDays) {
        this.provider = provider == null ? "local" : provider.trim().toLowerCase(Locale.ROOT);
        this.bucket = bucket == null || bucket.isBlank() ? "edupath-demo" : bucket.trim();
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "edupath" : keyPrefix.trim();
        this.localRoot = Path.of(localRoot == null || localRoot.isBlank() ? "storage" : localRoot).normalize();
        this.accessKeyId = accessKeyId == null ? "" : accessKeyId.trim();
        this.accessKeySecret = accessKeySecret == null ? "" : accessKeySecret.trim();
        this.signingSecret = signingSecret == null || signingSecret.isBlank()
                ? "dev-edupath-storage-signing-secret"
                : signingSecret;
        this.lifecycleDays = Math.max(1, lifecycleDays);
    }

    public StoredObject store(
            String namespace, String originalFilename, String contentType, long contentLength) {
        return store(namespace, originalFilename, contentType, new byte[0], contentLength);
    }

    public StoredObject store(
            String namespace, String originalFilename, String contentType, byte[] content) {
        byte[] safeContent = content == null ? new byte[0] : content;
        return store(namespace, originalFilename, contentType, safeContent, safeContent.length);
    }

    private StoredObject store(
            String namespace, String originalFilename, String contentType, byte[] content, long contentLength) {
        String key = buildObjectKey(namespace, originalFilename);
        byte[] safeContent = content == null ? new byte[0] : content;
        if ("local".equals(provider)) {
            writeLocalObject(key, safeContent);
        } else if ("oss".equals(provider)) {
            putOssObject(key, safeContent);
        } else {
            throw new IllegalStateException("不支持的对象存储 provider: " + provider);
        }
        return new StoredObject(
                provider,
                bucket,
                key,
                buildObjectUrl(key),
                "stored",
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType,
                contentLength,
                OffsetDateTime.now(ZoneOffset.ofHours(8)));
    }

    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        if ("oss".equals(provider)) {
            withOssClient(client -> {
                client.deleteObject(bucket, objectKey);
                return null;
            });
            return;
        }
        try {
            Files.deleteIfExists(resolveLocalPath(objectKey));
        } catch (Exception exception) {
            throw new IllegalStateException("删除本地对象失败: " + objectKey, exception);
        }
    }

    public byte[] read(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return new byte[0];
        }
        if ("oss".equals(provider)) {
            return getOssObject(objectKey);
        }
        try {
            Path path = resolveLocalPath(objectKey);
            return Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
        } catch (Exception exception) {
            throw new IllegalStateException("读取本地对象失败: " + objectKey, exception);
        }
    }

    public SignedUrl signedDownloadUrl(String objectKey) {
        return signedDownloadUrl(objectKey, Duration.ofMinutes(15));
    }

    public SignedUrl signedDownloadUrl(String objectKey, Duration ttl) {
        if (objectKey == null || objectKey.isBlank()) {
            return new SignedUrl(null, null);
        }
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).plus(ttl == null ? Duration.ofMinutes(15) : ttl);
        if ("oss".equals(provider)) {
            URL url = withOssClient(client -> client.generatePresignedUrl(
                    bucket,
                    objectKey,
                    java.util.Date.from(expiresAt.toInstant())));
            return new SignedUrl(url.toString(), expiresAt);
        }
        long expires = expiresAt.toEpochSecond();
        String signature = signature(objectKey, expires);
        return new SignedUrl(
                "/api/storage/signed?key=" + objectKey + "&expires=" + expires + "&signature=" + signature,
                expiresAt);
    }

    public StoredObjectContent readSignedLocalObject(String objectKey, long expires, String signature) {
        if (!"local".equals(provider)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本地签名下载仅适用于 local 存储");
        }
        if (objectKey == null || objectKey.isBlank() || signature == null || signature.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "签名下载参数不完整");
        }
        long now = Instant.now().getEpochSecond();
        if (expires < now) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "下载链接已过期");
        }
        String expected = signature(objectKey, expires);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "下载链接签名无效");
        }
        byte[] content = read(objectKey);
        if (content.length == 0 && !Files.exists(resolveLocalPath(objectKey))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对象不存在");
        }
        return new StoredObjectContent(content, "application/octet-stream", filenameFromKey(objectKey));
    }

    public int sweepExpiredObjects() {
        if (!"local".equals(provider) || !Files.exists(localRoot)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(lifecycleDays));
        int deleted = 0;
        try (Stream<Path> paths = Files.walk(localRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                FileTime modifiedAt = Files.getLastModifiedTime(path);
                if (modifiedAt.toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(path);
                    deleted++;
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("清理过期本地对象失败", exception);
        }
        return deleted;
    }

    public String provider() {
        return provider;
    }

    public String bucket() {
        return bucket;
    }

    public Path localPathForTesting(String objectKey) {
        return resolveLocalPath(objectKey);
    }

    private void writeLocalObject(String key, byte[] content) {
        try {
            Path path = resolveLocalPath(key);
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (Exception exception) {
            throw new IllegalStateException("写入本地对象失败: " + key, exception);
        }
    }

    private Path resolveLocalPath(String key) {
        Path resolved = localRoot.resolve(key).normalize();
        if (!resolved.startsWith(localRoot)) {
            throw new IllegalArgumentException("对象 key 不合法");
        }
        return resolved;
    }

    private String buildObjectKey(String namespace, String originalFilename) {
        String safeNamespace = sanitizePathSegment(namespace == null || namespace.isBlank() ? "objects" : namespace);
        String safeName = sanitizeFilename(originalFilename == null || originalFilename.isBlank()
                ? "unnamed-object"
                : originalFilename);
        return keyPrefix + "/" + safeNamespace + "/" + UUID.randomUUID() + "-" + safeName;
    }

    private String buildObjectUrl(String key) {
        if (endpoint.isBlank()) {
            return null;
        }
        String normalizedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return normalizedEndpoint + "/" + bucket + "/" + URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    private String sanitizePathSegment(String value) {
        String sanitized = value.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-+", "-");
        return sanitized.isBlank() ? "objects" : sanitized;
    }

    private String sanitizeFilename(String value) {
        String normalized = value.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String filename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String sanitized = filename.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-+", "-");
        sanitized = sanitized.replaceAll("^\\.+", "").replaceAll("\\.+$", "");
        return sanitized.isBlank() ? "unnamed-object" : sanitized;
    }

    private void putOssObject(String key, byte[] content) {
        withOssClient(client -> {
            client.putObject(bucket, key, new ByteArrayInputStream(content == null ? new byte[0] : content));
            return null;
        });
    }

    private byte[] getOssObject(String key) {
        return withOssClient(client -> {
            OSSObject object = client.getObject(bucket, key);
            try (var input = object.getObjectContent()) {
                return input.readAllBytes();
            }
        });
    }

    private <T> T withOssClient(OssOperation<T> operation) {
        if (endpoint.isBlank() || accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            throw new IllegalStateException("OSS 存储需要配置 OSS_ENDPOINT、OSS_ACCESS_KEY_ID、OSS_ACCESS_KEY_SECRET");
        }
        OSS client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            return operation.apply(client);
        } catch (Exception exception) {
            throw new IllegalStateException("OSS 对象存储操作失败", exception);
        } finally {
            client.shutdown();
        }
    }

    private String signature(String objectKey, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((objectKey + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("生成对象下载签名失败", exception);
        }
    }

    private String filenameFromKey(String objectKey) {
        int slash = objectKey == null ? -1 : objectKey.lastIndexOf('/');
        return slash >= 0 ? objectKey.substring(slash + 1) : objectKey;
    }

    @FunctionalInterface
    private interface OssOperation<T> {
        T apply(OSS client) throws Exception;
    }

    public record StoredObject(
            String provider,
            String bucket,
            String objectKey,
            String objectUrl,
            String storageStatus,
            String contentType,
            long contentLength,
            OffsetDateTime storedAt) {}

    public record SignedUrl(String url, OffsetDateTime expiresAt) {}

    public record StoredObjectContent(byte[] content, String contentType, String filename) {}
}
