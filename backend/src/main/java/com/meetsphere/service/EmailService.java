// backend/src/main/java/com/meetsphere/service/EmailService.java
package com.meetsphere.service;

import com.meetsphere.exception.ApiException;
import com.meetsphere.util.InvitationTemplate;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EmailService — sends MeetSphere emails through SMTP.
 *
 * How it works:
 *  1. spring-boot-starter-mail auto-configures a {@link JavaMailSender} from the spring.mail.* properties
 *     (host, port, username, password, STARTTLS). We never create it ourselves and no credentials are in code.
 *  2. For each recipient we build a MIME message (HTML + plain-text alternative + inline logo).
 *  3. JavaMailSender opens ONE connection to the SMTP server, authenticates, and sends every message.
 *
 * Each recipient receives their own email, so recipients never see each other's addresses.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    public static final String INVITATION_SUBJECT = "You're Invited to a MeetSphere Meeting";
    private static final String LOGO_CONTENT_ID = "meetsphere-logo";
    private static final String JOIN_PATH = "/pages/meeting.html";

    /** Outcome of a send: who got the email and who did not (with a safe, user-facing reason). */
    public record SendResult(List<String> sent, Map<String, String> failed) { }

    private final JavaMailSender mailSender;
    private final String smtpUsername;
    private final String fromAddress;
    private final String frontendBaseUrl;
    private final String htmlTemplate;

    public EmailService(JavaMailSender mailSender,
                        @Value("${spring.mail.username:}") String smtpUsername,
                        @Value("${app.mail.from:}") String fromAddress,
                        @Value("${app.frontend.base-url:http://localhost:8080}") String frontendBaseUrl) {
        this.mailSender = mailSender;
        this.smtpUsername = smtpUsername == null ? "" : smtpUsername.trim();
        this.fromAddress = fromAddress == null ? "" : fromAddress.trim();
        this.frontendBaseUrl = frontendBaseUrl == null ? "" : frontendBaseUrl.trim();
        try {
            this.htmlTemplate = StreamUtils.copyToString(
                    new ClassPathResource("mail/meeting-invitation.html").getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Email template mail/meeting-invitation.html is missing", e);
        }
        // One line at start-up so a missing / wrong setting is obvious in the console
        if (isConfigured()) {
            log.info("Email invitations ENABLED — SMTP account {}, sender {}", maskEmail(this.smtpUsername), maskEmail(senderAddress()));
        } else {
            log.warn("Email invitations DISABLED — set MAIL_USERNAME and MAIL_PASSWORD (see README, 'Email invitations')");
        }
    }

    /** True when an SMTP account (and therefore a sender address) has been configured. */
    public boolean isConfigured() {
        return !smtpUsername.isEmpty() && !senderAddress().isEmpty();
    }

    /** MAIL_FROM if set, otherwise the SMTP account itself (most providers only allow that address anyway). */
    private String senderAddress() {
        return fromAddress.isEmpty() ? smtpUsername : fromAddress;
    }

    /** The link in the email: <frontend base URL>/pages/meeting.html?code=<meeting code> — the page the app already uses to join. */
    public String buildJoinUrl(String meetingCode) {
        String base = frontendBaseUrl.replaceAll("/+$", "");
        return base + JOIN_PATH + "?code=" + URLEncoder.encode(meetingCode, StandardCharsets.UTF_8);
    }

    /**
     * Sends a meeting invitation to every recipient.
     *
     * @return which recipients were sent / failed
     * @throws ApiException 503 if SMTP is not configured or the SMTP login is rejected
     */
    public SendResult sendMeetingInvitations(String meetingTitle, String hostName,
                                             String meetingCode, List<String> recipients) {
        if (!isConfigured()) {
            throw new ApiException("Email invitations are not set up on the server yet. "
                    + "Ask the administrator to configure the SMTP settings.", 503);
        }

        String title = (meetingTitle == null || meetingTitle.isBlank()) ? "MeetSphere Meeting" : meetingTitle.trim();
        String host  = (hostName == null || hostName.isBlank()) ? "A MeetSphere user" : hostName.trim();
        String joinUrl = buildJoinUrl(meetingCode);

        String html = InvitationTemplate.renderHtml(htmlTemplate, Map.of(
                "MEETING_TITLE", title, "HOST_NAME", host, "MEETING_CODE", meetingCode, "JOIN_URL", joinUrl));
        String text = InvitationTemplate.renderText(title, host, meetingCode, joinUrl);

        // message → recipient, so a failure reported by JavaMail can be traced back to an address
        Map<MimeMessage, String> messages = new LinkedHashMap<>();
        try {
            for (String to : recipients) {
                messages.put(buildMessage(to, text, html), to);
            }
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Could not build invitation email: {}", e.getClass().getSimpleName());
            throw new ApiException("The invitation email could not be prepared.", 500);
        }

        Map<String, String> failed = new LinkedHashMap<>();
        try {
            mailSender.send(messages.keySet().toArray(new MimeMessage[0]));

        } catch (MailAuthenticationException e) {
            // Wrong username / password (Gmail: needs an App Password). Never log or return credentials.
            // The SMTP server's own reply (e.g. "535 5.7.8 Username and Password not accepted" or
            // "534 5.7.9 Application-specific password required") says exactly what is wrong. It contains no credentials.
            log.error("SMTP login rejected for account {} — server said: {}. Check MAIL_USERNAME / MAIL_PASSWORD "
                    + "(Gmail needs a 16-character App Password, created for THIS account, typed without spaces or quotes).",
                    maskEmail(smtpUsername), summarize(e));
            throw new ApiException("The email service is not configured correctly. "
                    + "Please contact the administrator.", 503);

        } catch (MailSendException e) {
            // Some or all messages failed (connection problem, address rejected, ...).
            Map<Object, Exception> failedMessages = e.getFailedMessages();
            if (failedMessages == null || failedMessages.isEmpty()) {
                messages.values().forEach(r -> failed.put(r, "Delivery failed."));
            } else {
                failedMessages.forEach((message, cause) -> {
                    String recipient = messages.get(message);
                    if (recipient != null) failed.put(recipient, reasonFor(cause));
                });
            }
            log.warn("{} of {} invitation email(s) failed: {}", failed.size(), recipients.size(), summarize(e));

        } catch (MailException e) {
            messages.values().forEach(r -> failed.put(r, "Delivery failed."));
            log.warn("Invitation emails failed: {}", summarize(e));
        }

        List<String> sent = recipients.stream().filter(r -> !failed.containsKey(r)).toList();
        log.info("Meeting invitation: {} sent, {} failed", sent.size(), failed.size());
        return new SendResult(sent, failed);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private MimeMessage buildMessage(String to, String text, String html)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = mailSender.createMimeMessage();
        // MIXED_RELATED = text alternatives + inline images (the logo) in one message
        MimeMessageHelper helper = new MimeMessageHelper(
                message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
        helper.setFrom(senderAddress(), "MeetSphere");
        helper.setTo(to);                       // already validated: no CR/LF, no extra addresses
        helper.setSubject(INVITATION_SUBJECT);
        helper.setText(text, html);             // plain text first, HTML second; must be set before addInline
        helper.addInline(LOGO_CONTENT_ID, new ClassPathResource("mail/logo.png"));
        return message;
    }

    /** Safe text for the user — never the raw SMTP server response. */
    private String reasonFor(Exception cause) {
        return (cause instanceof SendFailedException)
                ? "The mail server rejected this address."
                : "Delivery failed.";
    }

    /** a***@gmail.com — enough to recognise the account in the log without printing the full address. */
    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    /** One-line description for the server log (exception type + root cause message, no stack trace, no secrets). */
    private String summarize(Exception e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        String msg = root.getMessage() == null ? "" : root.getMessage().replaceAll("\\s+", " ");
        if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
        return root.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg);
    }
}
