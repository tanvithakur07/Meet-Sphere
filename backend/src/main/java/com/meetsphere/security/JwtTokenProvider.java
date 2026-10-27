// backend/src/main/java/com/meetsphere/security/JwtTokenProvider.java
package com.meetsphere.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;

/**
 * JwtTokenProvider — utility class to generate and validate JWT tokens.
 *
 * Flow:
 *   1. User logs in → generateToken(email, passwordHash) → JWT string returned to browser
 *   2. Browser sends JWT in Authorization: Bearer <token> header
 *   3. JwtAuthenticationFilter extracts + validates token on every request
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    // Injected from application.properties
    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration}")
    private long jwtExpirationMs;

    /** Refuse to start with a missing/short secret instead of failing on the first login. */
    @PostConstruct
    void checkSecret() {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "app.jwt.secret is missing or shorter than 32 bytes. Set the JWT_SECRET environment "
              + "variable or put app.jwt.secret in backend/application-local.properties (see README).");
        }
    }

    /** Name of the JWT claim that holds the credential stamp (see credentialStamp). */
    private static final String CLAIM_STAMP = "stamp";

    /**
     * Generates a signed JWT for a user.
     *
     * Besides the usual subject / issued-at / expiry, the token carries a "credential stamp" derived from the user's
     * CURRENT password hash. When the user changes their password the stamp changes, so every token issued before
     * the change stops being accepted (JwtAuthenticationFilter and JwtHandshakeInterceptor both check it).
     * That gives us token revocation on password change without a new database column.
     *
     * @param email        the user's email (becomes the token subject)
     * @param passwordHash the user's stored BCrypt hash (never put into the token itself — only the stamp derived from it)
     */
    public String generateToken(String email, String passwordHash) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .setSubject(email)                          // payload: who this token belongs to
                .claim(CLAIM_STAMP, credentialStamp(passwordHash))
                .setIssuedAt(now)                           // iat claim
                .setExpiration(expiry)                      // exp claim (24 hours by default)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * True if the token was issued for the user's CURRENT password. A token without a stamp (issued before this
     * feature existed) or with an old stamp (password changed since) is not current.
     */
    public boolean isTokenCurrent(String token, String currentPasswordHash) {
        try {
            String claimed = parseClaims(token).get(CLAIM_STAMP, String.class);
            if (claimed == null || currentPasswordHash == null) return false;
            return MessageDigest.isEqual(                   // constant-time comparison
                    claimed.getBytes(StandardCharsets.UTF_8),
                    credentialStamp(currentPasswordHash).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * HMAC-SHA256(secret, passwordHash), shortened to 16 bytes, Base64URL. Keyed with the server's JWT secret, so the
     * value in a token reveals nothing about the password hash and cannot be computed by anyone without the secret.
     */
    String credentialStamp(String passwordHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] full = mac.doFinal(passwordHash.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(full, 16));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public String getEmailFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Returns true if the token signature is valid and not expired.
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("JWT unsupported: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("JWT malformed: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("JWT invalid: {}", e.getMessage());
        }
        return false;
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key getSigningKey() {
        // Convert secret string to a cryptographic HMAC key
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }
}
