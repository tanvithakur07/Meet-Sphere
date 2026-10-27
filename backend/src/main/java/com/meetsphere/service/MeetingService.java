// backend/src/main/java/com/meetsphere/service/MeetingService.java
package com.meetsphere.service;

import com.meetsphere.exception.ApiException;
import com.meetsphere.model.*;
import com.meetsphere.repository.*;
import com.meetsphere.util.EmailAddressUtil;
import com.meetsphere.util.SlidingWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MeetingService — business logic for creating, joining, ending and leaving meetings.
 *
 * Meeting code format: "xxxx-xxxx-xxxx" (random alphanumeric — easy to share)
 */
@Service
public class MeetingService {

    // SecureRandom: the code is the only "secret" protecting a room, so it must be unpredictable
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private MeetingRepository meetingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MeetingParticipantRepository participantRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private EmailService emailService;

    // Invitation limits: protects the SMTP account (and recipients) from being used as a spam relay
    private static final int MAX_INVITES_PER_REQUEST = 10;
    private static final int MAX_INVITES_PER_HOUR    = 30;
    private final SlidingWindowRateLimiter inviteLimiter =
            new SlidingWindowRateLimiter(MAX_INVITES_PER_HOUR, 60L * 60 * 1000);

    // ── Create Meeting ────────────────────────────────────────────────────

    public Map<String, Object> createMeeting(String title, Long hostId) {
        User host = userRepository.findById(hostId)
                .orElseThrow(() -> new ApiException("Host user not found"));

        String code;
        do {
            code = generateMeetingCode();
        } while (meetingRepository.existsByMeetingCode(code));

        Meeting meeting = new Meeting();
        meeting.setMeetingCode(code);
        meeting.setTitle(title != null && !title.isBlank() ? title.trim() : "MeetSphere Meeting");
        meeting.setHost(host);
        meetingRepository.save(meeting);

        Map<String, Object> response = new HashMap<>();
        response.put("meetingCode", code);
        response.put("meetingId", meeting.getId());
        response.put("title", meeting.getTitle());
        response.put("hostName", host.getName());
        response.put("hostId", host.getId());
        return response;
    }

    // ── Join Meeting ──────────────────────────────────────────────────────

    /**
     * Validates the code, records the participant and returns meeting info
     * plus the latest chat history.
     */
    @Transactional
    public Map<String, Object> joinMeeting(String meetingCode, Long userId) {
        Meeting meeting = ensureParticipant(meetingCode, userId);

        // Latest 100 messages, oldest first
        List<ChatMessage> history = new ArrayList<>(
                chatMessageRepository.findTop100ByMeetingOrderBySentAtDesc(meeting));
        Collections.reverse(history);

        List<Map<String, Object>> chatHistory = history.stream().map(cm -> {
            Map<String, Object> m = new HashMap<>();
            m.put("senderId", cm.getSenderId());
            m.put("senderName", cm.getSenderName());
            m.put("content", cm.getContent());
            m.put("sentAt", cm.getSentAt() != null ? cm.getSentAt().toString() : null);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("meetingCode", meeting.getMeetingCode());
        response.put("meetingId", meeting.getId());
        response.put("title", meeting.getTitle());
        response.put("hostName", meeting.getHost().getName());
        response.put("hostId", meeting.getHost().getId());
        response.put("chatHistory", chatHistory);
        return response;
    }

    /**
     * Ensures the meeting exists and is active, and that the user has an open
     * participant record. Idempotent — used by both the REST join and the
     * WebSocket JOIN (so the two can never disagree).
     */
    @Transactional
    public Meeting ensureParticipant(String meetingCode, Long userId) {
        Meeting meeting = meetingRepository.findByMeetingCode(normalize(meetingCode))
                .orElseThrow(() -> new ApiException("Meeting not found", 404));

        if (!Boolean.TRUE.equals(meeting.getIsActive())) {
            throw new ApiException("This meeting has ended.", 410);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException("User not found", 404));

        if (participantRepository.findByMeetingAndUserAndLeftAtIsNull(meeting, user).isEmpty()) {
            MeetingParticipant participant = new MeetingParticipant();
            participant.setMeeting(meeting);
            participant.setUser(user);
            participantRepository.save(participant);
        }
        return meeting;
    }

    // ── Leave Meeting ─────────────────────────────────────────────────────

    @Transactional
    public void leaveMeeting(String meetingCode, Long userId) {
        meetingRepository.findByMeetingCode(normalize(meetingCode)).ifPresent(meeting ->
            userRepository.findById(userId).ifPresent(user ->
                participantRepository
                    .findByMeetingAndUserAndLeftAtIsNull(meeting, user)
                    .ifPresent(p -> {
                        p.setLeftAt(LocalDateTime.now());
                        participantRepository.save(p);
                    })));
    }

    // ── End Meeting (host only) ───────────────────────────────────────────

    /**
     * Ends the meeting for everyone. Only the host may do this.
     * After this, joinMeeting() rejects the code with "This meeting has ended."
     */
    @Transactional
    public void endMeeting(String meetingCode, Long userId) {
        Meeting meeting = meetingRepository.findByMeetingCode(normalize(meetingCode))
                .orElseThrow(() -> new ApiException("Meeting not found", 404));

        if (!meeting.getHost().getId().equals(userId)) {
            throw new ApiException("Only the host can end the meeting.", 403);
        }

        meeting.setIsActive(false);
        meetingRepository.save(meeting);

        LocalDateTime now = LocalDateTime.now();
        for (MeetingParticipant p : participantRepository.findActiveByMeeting(meeting)) {
            p.setLeftAt(now);
            participantRepository.save(p);
        }
    }

    // ── Invite by Email (host only) ───────────────────────────────────────

    /**
     * Emails a meeting invitation to one or more people.
     *
     * The meeting title, host name and code come from the DATABASE — nothing about the meeting is
     * taken from the request, so a client cannot forge an invitation that looks like it came from someone else.
     *
     * Order of checks:  meeting exists (404) → caller is the host (403) → meeting still active (410)
     *                   → addresses valid (400) → SMTP configured (503) → not over the rate limit (429) → send.
     *
     * @return { message, sent: [emails], failed: [{email, reason}] }
     */
    public Map<String, Object> inviteByEmail(String meetingCode, Long userId, Object rawEmails) {
        Meeting meeting = meetingRepository.findByMeetingCodeWithHost(normalize(meetingCode))
                .orElseThrow(() -> new ApiException("Meeting not found", 404));

        if (!meeting.getHost().getId().equals(userId)) {
            throw new ApiException("Only the host can invite people to this meeting.", 403);
        }
        if (!Boolean.TRUE.equals(meeting.getIsActive())) {
            throw new ApiException("This meeting has ended.", 410);
        }

        List<String> recipients = EmailAddressUtil.parseRecipients(rawEmails, MAX_INVITES_PER_REQUEST);

        if (!emailService.isConfigured()) {
            throw new ApiException("Email invitations are not set up on the server yet. "
                    + "Ask the administrator to configure the SMTP settings.", 503);
        }
        if (!inviteLimiter.tryAcquire(userId, recipients.size())) {
            throw new ApiException("You've reached the limit of " + MAX_INVITES_PER_HOUR
                    + " invitations per hour. Please try again later.", 429);
        }

        EmailService.SendResult result = emailService.sendMeetingInvitations(
                meeting.getTitle(), meeting.getHost().getName(), meeting.getMeetingCode(), recipients);

        List<Map<String, String>> failed = new ArrayList<>();
        result.failed().forEach((email, reason) -> failed.add(Map.of("email", email, "reason", reason)));

        String message;
        if (result.sent().isEmpty()) {
            message = "We couldn't deliver the invitation. Please check the address and try again.";
        } else if (failed.isEmpty()) {
            message = result.sent().size() == 1
                    ? "Invitation sent to 1 person."
                    : "Invitations sent to " + result.sent().size() + " people.";
        } else {
            message = "Sent to " + result.sent().size() + " of " + recipients.size()
                    + " people. Some invitations could not be delivered.";
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", message);
        response.put("sent", result.sent());
        response.put("failed", failed);
        return response;
    }

    // ── Get Participants ──────────────────────────────────────────────────

    @Transactional
    public List<Map<String, Object>> getParticipants(String meetingCode) {
        Meeting meeting = meetingRepository.findByMeetingCode(normalize(meetingCode))
                .orElseThrow(() -> new ApiException("Meeting not found", 404));

        return participantRepository.findActiveByMeeting(meeting)
                .stream()
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("userId", p.getUser().getId());
                    m.put("name", p.getUser().getName());
                    m.put("joinedAt", p.getJoinedAt().toString());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase();
    }

    /** Generates a code like "ab3d-e7gh-ij2l" using a cryptographically secure RNG. */
    private String generateMeetingCode() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int segment = 0; segment < 3; segment++) {
            if (segment > 0) sb.append("-");
            for (int i = 0; i < 4; i++) {
                sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
            }
        }
        return sb.toString();
    }
}
