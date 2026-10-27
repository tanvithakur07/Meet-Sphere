package com.meetsphere.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {

    @Test
    void acceptsAGoodPassword() {
        assertNull(PasswordPolicy.validate("Sunrise2026"));
        assertNull(PasswordPolicy.validate("correct horse 7 battery"));      // spaces inside are fine
        assertNull(PasswordPolicy.validate("पासवर्ड12345"));                    // non-Latin letters count as letters
    }

    @Test
    void rejectsEmptyOrNull() {
        assertNotNull(PasswordPolicy.validate(null));
        assertNotNull(PasswordPolicy.validate(""));
    }

    @Test
    void rejectsTooShort() {
        assertNotNull(PasswordPolicy.validate("Abc1234"));                   // 7 characters
        assertNull(PasswordPolicy.validate("Abc12345"));                     // exactly 8
    }

    @Test
    void rejectsPasswordsMissingALetterOrADigit() {
        assertNotNull(PasswordPolicy.validate("12345678"));
        assertNotNull(PasswordPolicy.validate("abcdefgh"));
        assertNotNull(PasswordPolicy.validate("!!!!????"));
    }

    @Test
    void rejectsMoreThan72BytesBecauseBcryptWouldTruncateIt() {
        assertNull(PasswordPolicy.validate("a1".repeat(36)));                // 72 bytes: allowed
        assertNotNull(PasswordPolicy.validate("a1".repeat(36) + "x"));       // 73 bytes: refused
        assertNotNull(PasswordPolicy.validate("é1".repeat(25) + "abc"));     // each "é1" is 3 bytes → 78 bytes
    }

    @Test
    void rejectsControlCharacters() {
        assertNotNull(PasswordPolicy.validate("Passw0rd\nline2"));
        assertNotNull(PasswordPolicy.validate("Passw0rd\t"));
    }
}
