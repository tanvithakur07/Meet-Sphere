// backend/src/main/java/com/meetsphere/util/PasswordPolicy.java
package com.meetsphere.util;

import java.nio.charset.StandardCharsets;

/**
 * Rules for a NEW password (used by "Change Password").
 *
 *  - 8 to 72 bytes. BCrypt only looks at the first 72 bytes, so anything longer would be silently
 *    truncated — we refuse it instead of pretending the extra characters count.
 *  - at least one letter and one digit
 *  - no control characters (tabs, new lines, ...)
 *
 * Registration keeps its existing, more lenient rule (6+ characters); this stricter policy is applied when
 * a user chooses a new password. The same rules are checked in js/account.js for instant feedback.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES  = 72;

    private PasswordPolicy() { }

    /** @return a user-facing message describing the first broken rule, or null if the password is acceptable */
    public static String validate(String password) {
        if (password == null || password.isEmpty()) return "Please enter a new password.";
        if (password.length() < MIN_LENGTH) return "The new password must be at least " + MIN_LENGTH + " characters long.";
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return "The new password is too long (maximum " + MAX_BYTES + " bytes).";

        boolean letter = false, digit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isISOControl(c)) return "The new password contains characters that are not allowed.";
            if (Character.isLetter(c)) letter = true;
            if (Character.isDigit(c))  digit = true;
        }
        if (!letter || !digit) return "The new password must contain at least one letter and one number.";
        return null;
    }
}
