package com.meetsphere.service;

import com.meetsphere.exception.ApiException;
import com.meetsphere.model.User;
import com.meetsphere.repository.UserRepository;
import com.meetsphere.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AuthService.changePassword — real AuthService, real BCrypt, real JwtTokenProvider;
 * only the database repository is replaced by an in-memory fake (no Mockito needed).
 */
class AuthServiceChangePasswordTest {

    private static final String OLD_PW = "OldPassw0rd";
    private static final String NEW_PW = "BrandNew2026";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);      // low cost = fast tests; the app uses 12
    private final Map<String, User> usersByEmail = new HashMap<>();
    private final List<String> savedEmails = new ArrayList<>();
    private AuthService service;
    private JwtTokenProvider jwt;

    private User alice, bob;

    private void setUp() throws Exception {
        alice = user(1L, "Alice", "alice@example.com", OLD_PW);
        bob   = user(2L, "Bob",   "bob@example.com",   "BobSecret99");

        UserRepository repo = (UserRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[] { UserRepository.class }, (proxy, m, a) -> {
                    switch (m.getName()) {
                        case "findByEmail": return Optional.ofNullable(usersByEmail.get((String) a[0]));
                        case "existsByEmail": return usersByEmail.containsKey((String) a[0]);
                        case "save": savedEmails.add(((User) a[0]).getEmail()); return a[0];
                        default: throw new UnsupportedOperationException(m.getName());
                    }
                });

        jwt = new JwtTokenProvider();
        set(jwt, "jwtSecret", "unit-test-secret-0123456789-abcdefghijklmnop");
        set(jwt, "jwtExpirationMs", 3_600_000L);

        service = new AuthService();
        set(service, "userRepository", repo);
        set(service, "passwordEncoder", encoder);
        set(service, "jwtTokenProvider", jwt);
    }

    private User user(long id, String name, String email, String plainPassword) {
        User u = new User(name, email, encoder.encode(plainPassword));
        u.setId(id);
        usersByEmail.put(email, u);
        return u;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private int statusOf(String email, String current, String next, String confirm) {
        try {
            service.changePassword(email, current, next, confirm);
            return 200;
        } catch (ApiException e) {
            return e.getStatus();
        }
    }

    @Test
    void successfulChangeStoresABcryptHashAndOldPasswordStopsWorking() throws Exception {
        setUp();
        String oldHash = alice.getPassword();
        Map<String, Object> result = service.changePassword("alice@example.com", OLD_PW, NEW_PW, NEW_PW);

        String stored = alice.getPassword();
        assertNotEquals(oldHash, stored);
        assertNotEquals(NEW_PW, stored);                                   // never plain text
        assertTrue(stored.startsWith("$2"));                               // BCrypt hash
        assertTrue(encoder.matches(NEW_PW, stored));                       // new password works
        assertFalse(encoder.matches(OLD_PW, stored));                      // old password no longer works
        assertEquals(List.of("alice@example.com"), savedEmails);
        assertEquals("Your password was changed successfully.", result.get("message"));
    }

    @Test
    void responseContainsOnlyAMessageAndAFreshToken() throws Exception {
        setUp();
        Map<String, Object> result = service.changePassword("alice@example.com", OLD_PW, NEW_PW, NEW_PW);
        assertEquals(new TreeSet<>(List.of("message", "token")), new TreeSet<>(result.keySet()));
        assertFalse(result.toString().contains(NEW_PW));
        assertFalse(result.toString().contains(OLD_PW));
        assertFalse(result.toString().contains(alice.getPassword()));      // no hash in the response
    }

    @Test
    void oldTokensAreRevokedAndTheReturnedTokenWorks() throws Exception {
        setUp();
        String oldToken = jwt.generateToken("alice@example.com", alice.getPassword());
        assertTrue(jwt.isTokenCurrent(oldToken, alice.getPassword()));

        String newToken = (String) service.changePassword("alice@example.com", OLD_PW, NEW_PW, NEW_PW).get("token");

        assertFalse(jwt.isTokenCurrent(oldToken, alice.getPassword()));    // revoked
        assertTrue(jwt.isTokenCurrent(newToken, alice.getPassword()));     // this session stays signed in
        assertEquals("alice@example.com", jwt.getEmailFromToken(newToken));
    }

    @Test
    void wrongCurrentPasswordIsRejectedAndNothingChanges() throws Exception {
        setUp();
        String before = alice.getPassword();
        assertEquals(403, statusOf("alice@example.com", "WrongPassw0rd", NEW_PW, NEW_PW));
        assertEquals(before, alice.getPassword());
        assertTrue(savedEmails.isEmpty());
    }

    @Test
    void mismatchedConfirmationIsRejected() throws Exception {
        setUp();
        String before = alice.getPassword();
        assertEquals(400, statusOf("alice@example.com", OLD_PW, NEW_PW, NEW_PW + "x"));
        assertEquals(before, alice.getPassword());
    }

    @Test
    void emptyOrMissingFieldsAreRejected() throws Exception {
        setUp();
        assertEquals(400, statusOf("alice@example.com", null, NEW_PW, NEW_PW));
        assertEquals(400, statusOf("alice@example.com", "", NEW_PW, NEW_PW));
        assertEquals(400, statusOf("alice@example.com", OLD_PW, null, null));
        assertEquals(400, statusOf("alice@example.com", OLD_PW, "", ""));
        assertEquals(400, statusOf("alice@example.com", OLD_PW, NEW_PW, ""));
        assertTrue(savedEmails.isEmpty());
    }

    @Test
    void weakOrInvalidNewPasswordsAreRejected() throws Exception {
        setUp();
        for (String weak : new String[] { "Ab1", "abcdefgh", "12345678", "Passw0rd\n!", "a1".repeat(40) }) {
            assertEquals(400, statusOf("alice@example.com", OLD_PW, weak, weak), "should reject: " + weak.replace("\n", "\\n"));
        }
        assertTrue(encoder.matches(OLD_PW, alice.getPassword()));
        assertTrue(savedEmails.isEmpty());
    }

    @Test
    void newPasswordMustDifferFromTheCurrentOne() throws Exception {
        setUp();
        assertEquals(400, statusOf("alice@example.com", OLD_PW, OLD_PW, OLD_PW));
        assertTrue(savedEmails.isEmpty());
    }

    @Test
    void onlyTheAuthenticatedUsersPasswordChanges() throws Exception {
        setUp();
        String bobBefore = bob.getPassword();
        service.changePassword("alice@example.com", OLD_PW, NEW_PW, NEW_PW);
        assertEquals(bobBefore, bob.getPassword());                        // Bob untouched
        assertEquals(List.of("alice@example.com"), savedEmails);

        // Alice cannot target Bob: the account is taken from the token's email, and knowing Bob's password is required anyway
        assertEquals(403, statusOf("alice@example.com", "BobSecret99", "AnotherPass1", "AnotherPass1"));
        assertEquals(bobBefore, bob.getPassword());
    }

    @Test
    void unknownAccountIsRejected() throws Exception {
        setUp();
        assertEquals(401, statusOf("ghost@example.com", OLD_PW, NEW_PW, NEW_PW));
    }

    @Test
    void repeatedWrongGuessesAreThrottled() throws Exception {
        setUp();
        for (int i = 0; i < 10; i++) {
            assertEquals(403, statusOf("alice@example.com", "Guess" + i + "xyz", NEW_PW, NEW_PW));
        }
        assertEquals(429, statusOf("alice@example.com", OLD_PW, NEW_PW, NEW_PW));   // even the correct password is refused now
        assertTrue(encoder.matches(OLD_PW, alice.getPassword()));
    }
}
