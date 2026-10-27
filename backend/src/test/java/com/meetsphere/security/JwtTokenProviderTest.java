package com.meetsphere.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-0123456789-abcdefghijklmnop";
    private static final String HASH_A = "$2a$12$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012345";
    private static final String HASH_B = "$2a$12$zyxwvutsrqponmlkjihgfeZYXWVUTSRQPONMLKJIHGFEDCBA543210";

    private JwtTokenProvider provider() throws Exception {
        JwtTokenProvider p = new JwtTokenProvider();
        set(p, "jwtSecret", SECRET);
        set(p, "jwtExpirationMs", 3_600_000L);
        return p;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void tokenIsValidAndCurrentForTheSamePasswordHash() throws Exception {
        JwtTokenProvider p = provider();
        String token = p.generateToken("a@example.com", HASH_A);
        assertTrue(p.validateToken(token));
        assertEquals("a@example.com", p.getEmailFromToken(token));
        assertTrue(p.isTokenCurrent(token, HASH_A));
    }

    @Test
    void tokenStopsBeingCurrentWhenThePasswordHashChanges() throws Exception {
        JwtTokenProvider p = provider();
        String oldToken = p.generateToken("a@example.com", HASH_A);
        assertFalse(p.isTokenCurrent(oldToken, HASH_B));                    // password changed → old token revoked
        assertTrue(p.isTokenCurrent(p.generateToken("a@example.com", HASH_B), HASH_B));
    }

    @Test
    void tokenWithoutAStampIsNotCurrent() throws Exception {
        // what the application issued before this feature existed
        String legacy = Jwts.builder().setSubject("a@example.com").setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256).compact();
        JwtTokenProvider p = provider();
        assertTrue(p.validateToken(legacy));                                // still a valid, signed token...
        assertFalse(p.isTokenCurrent(legacy, HASH_A));                      // ...but not accepted any more
    }

    @Test
    void garbageOrTamperedTokensAreNotCurrent() throws Exception {
        JwtTokenProvider p = provider();
        String token = p.generateToken("a@example.com", HASH_A);
        assertFalse(p.isTokenCurrent("not-a-jwt", HASH_A));
        assertFalse(p.isTokenCurrent(token + "x", HASH_A));
        assertFalse(p.isTokenCurrent(token, null));
    }

    @Test
    void tokenDoesNotContainThePasswordHash() throws Exception {
        JwtTokenProvider p = provider();
        String token = p.generateToken("a@example.com", HASH_A);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertFalse(payload.contains(HASH_A));
        assertFalse(payload.contains("$2a$"));
        assertTrue(payload.contains("\"stamp\""));
    }
}
