// backend/src/main/java/com/meetsphere/util/InvitationTemplate.java
package com.meetsphere.util;

import java.util.Map;

/**
 * Fills the invitation email templates.
 *
 * The HTML template lives in src/main/resources/mail/meeting-invitation.html and contains
 * placeholders such as {{MEETING_TITLE}}. Every value is HTML-escaped here, so a meeting title or
 * host name containing "<script>" can never become markup in the recipient's mailbox.
 */
public final class InvitationTemplate {

    private InvitationTemplate() { }

    /** Replaces each {{KEY}} in the template with the HTML-escaped value. */
    public static String renderHtml(String template, Map<String, String> values) {
        String html = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            html = html.replace("{{" + e.getKey() + "}}", escapeHtml(e.getValue()));
        }
        return html;
    }

    /** Plain-text alternative, shown by mail clients that do not display HTML. */
    public static String renderText(String meetingTitle, String hostName, String meetingCode, String joinUrl) {
        return "You're invited to a MeetSphere meeting\n\n"
             + hostName + " has invited you to join:\n\n"
             + "  " + meetingTitle + "\n\n"
             + "Join the meeting: " + joinUrl + "\n"
             + "Meeting code: " + meetingCode + "\n\n"
             + "You need a free MeetSphere account to join. If you don't have one yet, create it first -\n"
             + "after you sign in you will be taken straight to the meeting.\n\n"
             + "If you weren't expecting this invitation, you can safely ignore this email.\n";
    }

    public static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&'  -> sb.append("&amp;");
                case '<'  -> sb.append("&lt;");
                case '>'  -> sb.append("&gt;");
                case '"'  -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
