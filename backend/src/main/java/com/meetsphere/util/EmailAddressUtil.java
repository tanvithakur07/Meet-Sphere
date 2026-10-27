// backend/src/main/java/com/meetsphere/util/EmailAddressUtil.java
package com.meetsphere.util;

import com.meetsphere.exception.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation and parsing of recipient email addresses for meeting invitations.
 *
 * The backend validates everything again even though the frontend already does —
 * a client can always bypass the browser and call the API directly.
 */
public final class EmailAddressUtil {

    // Practical subset of RFC 5322: a dot-atom local part, and a domain with at least one dot.
    // No whitespace, quotes, commas, CR or LF are possible → no header injection through an address.
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
          + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
          + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");

    private EmailAddressUtil() { }

    /** True if the address looks like a deliverable mailbox address. */
    public static boolean isValid(String email) {
        if (email == null || email.length() > 254) return false;
        int at = email.lastIndexOf('@');
        if (at < 1 || at > 64) return false;                       // local part: 1..64 chars
        String local = email.substring(0, at);
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) return false;
        return EMAIL.matcher(email).matches();
    }

    /**
     * Turns the request's "emails" value into a clean list.
     *
     * @param raw a JSON array of strings, or one string separated by commas / semicolons / spaces / new lines
     * @param max maximum number of distinct recipients per request
     * @return distinct, trimmed, lower-cased addresses in the order they were entered
     * @throws ApiException (400) when nothing was supplied, there are too many, or any address is invalid
     */
    public static List<String> parseRecipients(Object raw, int max) {
        List<String> tokens = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) tokens.addAll(split(item.toString()));
            }
        } else if (raw instanceof String s) {
            tokens.addAll(split(s));
        }

        Set<String> unique = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        for (String token : tokens) {
            String email = token.toLowerCase(Locale.ROOT);
            if (isValid(email)) unique.add(email);
            else invalid.add(printable(token));
        }

        if (unique.isEmpty() && invalid.isEmpty()) {
            throw new ApiException("Please enter at least one email address.", 400);
        }
        if (!invalid.isEmpty()) {
            String shown = String.join(", ", invalid.subList(0, Math.min(3, invalid.size())));
            String more = invalid.size() > 3 ? " and " + (invalid.size() - 3) + " more" : "";
            throw new ApiException("These email addresses are not valid: " + shown + more + ".", 400);
        }
        if (unique.size() > max) {
            throw new ApiException("You can invite up to " + max + " people at a time.", 400);
        }
        return new ArrayList<>(unique);
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String part : SEPARATORS.split(s.trim())) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    /** Safe-to-echo version of user input: control characters removed and length capped. */
    private static String printable(String s) {
        String clean = s.replaceAll("\\p{Cntrl}", "");
        return clean.length() > 60 ? clean.substring(0, 60) + "…" : clean;
    }
}
