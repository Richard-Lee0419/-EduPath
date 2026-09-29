package com.edupath.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final String issuer;
    private final long ttlSeconds;

    public JwtService(
            ObjectMapper objectMapper,
            @Value("${edupath.jwt.secret}") String secret,
            @Value("${edupath.jwt.issuer:edupath}") String issuer,
            @Value("${edupath.jwt.ttl-seconds:86400}") long ttlSeconds) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(UserRepository.UserRecord user) {
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + ttlSeconds;
        return sign(
                Map.of("alg", "HS256", "typ", "JWT"),
                Map.of(
                        "iss", issuer,
                        "sub", Long.toString(user.id()),
                        "username", user.username(),
                        "role", user.role(),
                        "jti", UUID.randomUUID().toString(),
                        "iat", now,
                        "exp", expiresAt));
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public AuthPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("请先登录后再继续");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("登录状态无效，请重新登录");
        }
        String signedContent = parts[0] + "." + parts[1];
        String expectedSignature = base64Url(hmac(signedContent.getBytes(StandardCharsets.UTF_8)));
        if (!constantTimeEquals(expectedSignature, parts[2])) {
            throw new IllegalArgumentException("登录状态无效，请重新登录");
        }
        Map<?, ?> claims = readJson(base64UrlDecode(parts[1]));
        Object expValue = claims.get("exp");
        long exp = expValue instanceof Number number ? number.longValue() : 0L;
        if (Instant.now().getEpochSecond() > exp) {
            throw new IllegalArgumentException("登录状态已失效，请重新登录");
        }
        return new AuthPrincipal(
                Long.parseLong(String.valueOf(claims.get("sub"))),
                String.valueOf(claims.get("username")),
                String.valueOf(claims.get("role")),
                String.valueOf(claims.get("jti")),
                exp);
    }

    private String sign(Map<String, Object> header, Map<String, Object> payload) {
        String encodedHeader = base64Url(writeJson(header));
        String encodedPayload = base64Url(writeJson(payload));
        String content = encodedHeader + "." + encodedPayload;
        return content + "." + base64Url(hmac(content.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] writeJson(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JWT 序列化失败", exception);
        }
    }

    private Map<?, ?> readJson(byte[] value) {
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("登录状态无效，请重新登录", exception);
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception exception) {
            throw new IllegalStateException("JWT 签名失败", exception);
        }
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return PasswordServiceEquals.constantTimeEquals(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static final class PasswordServiceEquals {
        private static boolean constantTimeEquals(byte[] expected, byte[] actual) {
            if (expected.length != actual.length) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < expected.length; i++) {
                result |= expected[i] ^ actual[i];
            }
            return result == 0;
        }
    }

    public record AuthPrincipal(long userId, String username, String role, String jti, long expiresAtEpochSeconds) {}
}
