package com.meetsphere.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InvitationTemplateTest {

    @Test
    void escapesHtmlInValues() {
        String out = InvitationTemplate.renderHtml("<p>{{TITLE}}</p>",
                Map.of("TITLE", "<script>alert(1)</script> & \"q\" 'x'"));
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;q&quot; &#39;x&#39;</p>", out);
        assertFalse(out.contains("<script>"));
    }

    @Test
    void replacesEveryOccurrenceOfAPlaceholder() {
        String out = InvitationTemplate.renderHtml("{{A}} and {{A}} and {{B}}", Map.of("A", "1", "B", "2"));
        assertEquals("1 and 1 and 2", out);
    }

    @Test
    void plainTextContainsTheKeyFacts() {
        String t = InvitationTemplate.renderText("Team Standup", "Tanvi", "abcd-efgh-ijkl",
                "http://localhost:5500/pages/meeting.html?code=abcd-efgh-ijkl");
        assertTrue(t.contains("Team Standup"));
        assertTrue(t.contains("Tanvi"));
        assertTrue(t.contains("abcd-efgh-ijkl"));
        assertTrue(t.contains("http://localhost:5500/pages/meeting.html?code=abcd-efgh-ijkl"));
    }
}
