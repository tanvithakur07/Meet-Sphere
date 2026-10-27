package com.meetsphere.util;

import com.meetsphere.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmailAddressUtilTest {

    @Test
    void acceptsNormalAddresses() {
        assertTrue(EmailAddressUtil.isValid("alice@example.com"));
        assertTrue(EmailAddressUtil.isValid("first.last+tag@sub.example.co.in"));
        assertTrue(EmailAddressUtil.isValid("o'brien@example.org"));
    }

    @Test
    void rejectsMalformedAddresses() {
        for (String bad : new String[] {
                "", "plainaddress", "@example.com", "alice@", "alice@example", "alice@@example.com",
                "a b@example.com", ".alice@example.com", "alice.@example.com", "al..ice@example.com",
                "alice@-example.com", "alice@example..com", "alice@example.com\nBcc: evil@example.com",
                "alice@example.com,bob@example.com" }) {
            assertFalse(EmailAddressUtil.isValid(bad), "should be invalid: " + bad.replace("\n", "\\n"));
        }
        assertFalse(EmailAddressUtil.isValid(null));
        assertFalse(EmailAddressUtil.isValid("a".repeat(65) + "@example.com"));          // local part too long
        assertFalse(EmailAddressUtil.isValid("a@" + "b".repeat(250) + ".com"));           // whole address too long
    }

    @Test
    void parsesListAndTrimsLowercasesAndDeduplicates() {
        List<String> r = EmailAddressUtil.parseRecipients(
                List.of(" Alice@Example.com ", "bob@example.com", "ALICE@example.com"), 10);
        assertEquals(List.of("alice@example.com", "bob@example.com"), r);
    }

    @Test
    void parsesSingleStringWithMixedSeparators() {
        List<String> r = EmailAddressUtil.parseRecipients("a@x.com, b@x.com;c@x.com\nd@x.com e@x.com", 10);
        assertEquals(List.of("a@x.com", "b@x.com", "c@x.com", "d@x.com", "e@x.com"), r);
    }

    @Test
    void rejectsMissingOrEmptyInput() {
        assertEquals(400, assertThrows(ApiException.class, () -> EmailAddressUtil.parseRecipients(null, 10)).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> EmailAddressUtil.parseRecipients(List.of(), 10)).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> EmailAddressUtil.parseRecipients("  ,  ; ", 10)).getStatus());
    }

    @Test
    void rejectsInvalidAddressAndNamesIt() {
        ApiException e = assertThrows(ApiException.class,
                () -> EmailAddressUtil.parseRecipients(List.of("good@example.com", "not-an-email"), 10));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("not-an-email"));
    }

    @Test
    void rejectsTooManyRecipients() {
        ApiException e = assertThrows(ApiException.class,
                () -> EmailAddressUtil.parseRecipients(List.of("a@x.com", "b@x.com", "c@x.com"), 2));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("2"));
    }

    @Test
    void doesNotEchoControlCharacters() {
        ApiException e = assertThrows(ApiException.class,
                () -> EmailAddressUtil.parseRecipients(List.of("bad\u0007addr"), 10));
        assertFalse(e.getMessage().contains("\u0007"));
    }
}
