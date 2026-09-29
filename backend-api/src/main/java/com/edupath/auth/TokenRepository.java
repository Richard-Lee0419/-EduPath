package com.edupath.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public TokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveRefreshToken(String refreshToken, long userId, Instant expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO refresh_tokens (token_hash, user_id, status, expires_at, created_at)
                VALUES (?, ?, 'active', ?, CURRENT_TIMESTAMP)
                """,
                hash(refreshToken),
                userId,
                Timestamp.from(expiresAt));
    }

    public Optional<RefreshTokenRecord> findActiveRefreshToken(String refreshToken) {
        return jdbcTemplate
                .query(
                        """
                        SELECT id, token_hash, user_id, status, expires_at, created_at, revoked_at
                        FROM refresh_tokens
                        WHERE token_hash = ? AND status = 'active' AND expires_at > CURRENT_TIMESTAMP
                        """,
                        this::mapRefreshToken,
                        hash(refreshToken))
                .stream()
                .findFirst();
    }

    public void revokeRefreshToken(long id) {
        jdbcTemplate.update(
                """
                UPDATE refresh_tokens
                SET status = 'revoked', revoked_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                id);
    }

    public void revokeAllRefreshTokens(long userId) {
        jdbcTemplate.update(
                """
                UPDATE refresh_tokens
                SET status = 'revoked', revoked_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND status = 'active'
                """,
                userId);
    }

    public void revokeAccessToken(JwtService.AuthPrincipal principal) {
        if (principal.jti() == null || principal.jti().isBlank()) {
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO jwt_revocations (jti, user_id, expires_at, revoked_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                principal.jti(),
                principal.userId(),
                Timestamp.from(Instant.ofEpochSecond(principal.expiresAtEpochSeconds())));
    }

    public boolean isAccessTokenRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return true;
        }
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM jwt_revocations
                WHERE jti = ? AND expires_at > CURRENT_TIMESTAMP
                """,
                Long.class,
                jti);
        return count != null && count > 0;
    }

    private RefreshTokenRecord mapRefreshToken(ResultSet rs, int rowNum) throws SQLException {
        return new RefreshTokenRecord(
                rs.getLong("id"),
                rs.getString("token_hash"),
                rs.getLong("user_id"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("expires_at")),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("revoked_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("token hash 失败", exception);
        }
    }

    public record RefreshTokenRecord(
            long id,
            String tokenHash,
            long userId,
            String status,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt,
            OffsetDateTime revokedAt) {}
}
