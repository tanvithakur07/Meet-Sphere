// backend/src/main/java/com/meetsphere/controller/MeetingController.java
package com.meetsphere.controller;

import com.meetsphere.exception.ApiException;
import com.meetsphere.repository.UserRepository;
import com.meetsphere.service.MeetingService;
import com.meetsphere.websocket.SignalingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MeetingController — REST API for meeting room management.
 * All routes require JWT authentication (see SecurityConfig).
 *
 *   POST /api/meetings/create              → create new meeting
 *   POST /api/meetings/join/{code}         → join existing meeting
 *   POST /api/meetings/leave/{code}        → leave meeting
 *   POST /api/meetings/end/{code}          → host ends meeting for everyone
 *   POST /api/meetings/{code}/invite       → host emails meeting invitations  { "emails": ["a@x.com"] }
 *   GET  /api/meetings/{code}/participants → get active participants
 */
@RestController
@RequestMapping("/api/meetings")
@CrossOrigin(origins = "*")
public class MeetingController {

    private static final Logger log = LoggerFactory.getLogger(MeetingController.class);

    @Autowired private MeetingService meetingService;
    @Autowired private UserRepository userRepository;
    @Autowired private SignalingHandler signalingHandler;

    @PostMapping("/create")
    public ResponseEntity<?> createMeeting(@RequestBody(required = false) Map<String, String> body,
                                           Authentication auth) {
        try {
            Long userId = getUserId(auth);
            String title = (body != null) ? body.get("title") : null;
            return ResponseEntity.ok(meetingService.createMeeting(title, userId));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/join/{code}")
    public ResponseEntity<?> joinMeeting(@PathVariable String code, Authentication auth) {
        try {
            return ResponseEntity.ok(meetingService.joinMeeting(code, getUserId(auth)));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/leave/{code}")
    public ResponseEntity<?> leaveMeeting(@PathVariable String code, Authentication auth) {
        try {
            meetingService.leaveMeeting(code, getUserId(auth));
            return ResponseEntity.ok(Map.of("message", "Left meeting successfully"));
        } catch (Exception e) {
            return error(e);
        }
    }

    /** Host-only: marks the meeting inactive and tells everyone in the room. */
    @PostMapping("/end/{code}")
    public ResponseEntity<?> endMeeting(@PathVariable String code, Authentication auth) {
        try {
            meetingService.endMeeting(code, getUserId(auth));
            signalingHandler.notifyMeetingEnded(code.trim().toLowerCase());
            return ResponseEntity.ok(Map.of("message", "Meeting ended"));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * Host-only: send the meeting invitation to one or more email addresses.
     * 200 = at least one email was sent (see "failed" for any that were not),
     * 502 = none could be delivered, 400/403/404/410/429/503 = see MeetingService.inviteByEmail.
     */
    @PostMapping("/{code}/invite")
    public ResponseEntity<?> inviteByEmail(@PathVariable String code,
                                           @RequestBody(required = false) Map<String, Object> body,
                                           Authentication auth) {
        try {
            Object emails = (body != null) ? body.get("emails") : null;
            Map<String, Object> result = meetingService.inviteByEmail(code, getUserId(auth), emails);
            boolean nothingSent = ((List<?>) result.get("sent")).isEmpty();
            return ResponseEntity.status(nothingSent ? 502 : 200).body(result);
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/{code}/participants")
    public ResponseEntity<?> getParticipants(@PathVariable String code) {
        try {
            List<Map<String, Object>> participants = meetingService.getParticipants(code);
            return ResponseEntity.ok(participants);
        } catch (Exception e) {
            return error(e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Long getUserId(Authentication auth) {
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException("Authenticated user not found", 401))
                .getId();
    }

    /** Business errors show their message; anything unexpected is logged and hidden. */
    private ResponseEntity<?> error(Exception e) {
        if (e instanceof ApiException api) {
            return ResponseEntity.status(api.getStatus()).body(Map.of("error", api.getMessage()));
        }
        log.error("Unexpected error in MeetingController", e);
        return ResponseEntity.status(500).body(Map.of("error", "Something went wrong. Please try again."));
    }
}
