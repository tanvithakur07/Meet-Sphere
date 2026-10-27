// backend/src/main/java/com/meetsphere/websocket/SignalingHandler.java
package com.meetsphere.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetsphere.exception.ApiException;
import com.meetsphere.model.ChatMessage;
import com.meetsphere.repository.MeetingRepository;
import com.meetsphere.repository.ChatMessageRepository;
import com.meetsphere.service.MeetingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SignalingHandler — routes WebRTC signaling + chat messages.
 *
 * Security model:
 *  - The connection is authenticated in the handshake (JwtHandshakeInterceptor);
 *    userId and display name come from the database, never from the client.
 *  - JOIN is only accepted for an existing, active meeting.
 *  - Every forwarded message is stamped with the real senderId / senderName.
 *  - Chat is persisted with the real sender_id.
 *  - When a socket closes (tab closed, crash) the participant is marked as left.
 */
@Component
public class SignalingHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalingHandler.class);
    private static final int MAX_CHAT_LENGTH = 500;

    // roomCode → { sessionId → WebSocketSession }
    private final Map<String, Map<String, WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    // sessionId → roomCode
    private final Map<String, String> sessionRooms = new ConcurrentHashMap<>();
    // sessionId → last known mic/camera/screen state (so late joiners see the correct state)
    private final Map<String, Map<String, Object>> mediaStates = new ConcurrentHashMap<>();

    @Autowired private ObjectMapper objectMapper;
    @Autowired private MeetingRepository meetingRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private MeetingService meetingService;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        log.info("WS connected: {} (user {})", session.getId(), userId(session));

        // Tell the client its own session id (lets it recognise its own chat messages)
        SignalingMessage welcome = new SignalingMessage();
        welcome.setType("WELCOME");
        welcome.setSenderId(session.getId());
        welcome.setSenderName(userName(session));
        sendMessage(session, welcome);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WS closed: {} — {}", session.getId(), status);
        handleLeave(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS transport error {}: {}", session.getId(), exception.getMessage());
    }

    // ── Dispatcher ────────────────────────────────────────────────────────

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage raw) throws Exception {
        SignalingMessage msg;
        try {
            msg = objectMapper.readValue(raw.getPayload(), SignalingMessage.class);
        } catch (Exception e) {
            log.warn("Ignoring malformed WS message from {}", session.getId());
            return;
        }
        if (msg == null || msg.getType() == null) return;

        switch (msg.getType()) {
            case "JOIN"          -> handleJoin(session, msg);
            case "LEAVE"         -> handleLeave(session);
            case "OFFER", "ANSWER", "ICE_CANDIDATE" -> forwardToTarget(session, msg);
            case "CHAT"          -> handleChat(session, msg);
            case "MEDIA_STATE"   -> handleMediaState(session, msg);
            default              -> log.warn("Unknown type: {}", msg.getType());
        }
    }

    // ── JOIN ──────────────────────────────────────────────────────────────

    private void handleJoin(WebSocketSession session, SignalingMessage msg) throws IOException {
        String sessionId = session.getId();

        // Already in a room on this socket → ignore (prevents room hopping / duplicate joins)
        if (sessionRooms.containsKey(sessionId)) return;

        String roomCode = msg.getMeetingCode() == null ? "" : msg.getMeetingCode().trim().toLowerCase();
        Long userId = userId(session);
        String senderName = userName(session);

        // Validate the meeting exists & is active, and record the participant
        try {
            meetingService.ensureParticipant(roomCode, userId);
        } catch (ApiException e) {
            SignalingMessage err = new SignalingMessage();
            err.setType("ERROR");
            err.setPayload(e.getMessage());
            sendMessage(session, err);
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // Register the session
        roomSessions.computeIfAbsent(roomCode, k -> new ConcurrentHashMap<>()).put(sessionId, session);
        sessionRooms.put(sessionId, roomCode);
        Map<String, WebSocketSession> room = roomSessions.get(roomCode);

        log.info("JOIN: '{}' ({}) → room '{}'", senderName, sessionId, roomCode);

        // EXISTING_PEERS: everyone except the joiner
        List<Map<String, Object>> existingPeersList = new ArrayList<>();
        for (Map.Entry<String, WebSocketSession> entry : room.entrySet()) {
            if (!entry.getKey().equals(sessionId)) {
                Map<String, Object> peer = new HashMap<>();
                peer.put("sessionId", entry.getKey());
                peer.put("name", userName(entry.getValue()));
                // current mic / camera / screen-share state of that peer
                peer.put("mediaState", mediaStates.getOrDefault(entry.getKey(), defaultMediaState()));
                existingPeersList.add(peer);
            }
        }
        SignalingMessage existingPeers = new SignalingMessage();
        existingPeers.setType("EXISTING_PEERS");
        existingPeers.setPayload(existingPeersList);
        sendMessage(session, existingPeers);

        // USER_JOINED: tell everyone else (they will send the OFFERs)
        SignalingMessage joinNote = new SignalingMessage();
        joinNote.setType("USER_JOINED");
        joinNote.setSenderId(sessionId);
        joinNote.setSenderName(senderName);
        joinNote.setMeetingCode(roomCode);
        for (Map.Entry<String, WebSocketSession> entry : room.entrySet()) {
            if (!entry.getKey().equals(sessionId)) {
                sendQuietly(entry.getValue(), joinNote);
            }
        }
    }

    // ── LEAVE ─────────────────────────────────────────────────────────────

    private void handleLeave(WebSocketSession session) {
        String sessionId = session.getId();
        String roomCode  = sessionRooms.remove(sessionId);
        mediaStates.remove(sessionId);
        if (roomCode == null) return;

        Map<String, WebSocketSession> room = roomSessions.get(roomCode);
        boolean sameUserStillHere = false;
        if (room != null) {
            room.remove(sessionId);
            Long me = userId(session);
            for (WebSocketSession other : room.values()) {
                if (me != null && me.equals(userId(other))) sameUserStillHere = true;
            }
            if (room.isEmpty()) roomSessions.remove(roomCode);
        }

        String senderName = userName(session);

        SignalingMessage leaveMsg = new SignalingMessage();
        leaveMsg.setType("USER_LEFT");
        leaveMsg.setSenderId(sessionId);
        leaveMsg.setSenderName(senderName);
        leaveMsg.setMeetingCode(roomCode);
        broadcastToRoom(roomCode, sessionId, leaveMsg);

        // Record left_at even if the tab was just closed (no REST /leave call).
        // Skipped if the same user has another socket in the room (e.g. page refresh race).
        Long uid = userId(session);
        if (uid != null && !sameUserStillHere) {
            try {
                meetingService.leaveMeeting(roomCode, uid);
            } catch (Exception e) {
                log.warn("Could not record leave for user {}: {}", uid, e.getMessage());
            }
        }
        log.info("LEAVE: '{}' ({}) ← room '{}'", senderName, sessionId, roomCode);
    }

    /** Called by MeetingController after the host ends a meeting. */
    public void notifyMeetingEnded(String roomCode) {
        Map<String, WebSocketSession> room = roomSessions.get(roomCode);
        if (room == null) return;

        SignalingMessage ended = new SignalingMessage();
        ended.setType("MEETING_ENDED");
        ended.setMeetingCode(roomCode);

        for (WebSocketSession s : new ArrayList<>(room.values())) {
            sendQuietly(s, ended);
            try { s.close(CloseStatus.NORMAL); } catch (IOException ignored) { }
        }
    }

    // ── WebRTC forwarding (OFFER / ANSWER / ICE) ──────────────────────────

    private void forwardToTarget(WebSocketSession session, SignalingMessage msg) {
        String targetId = msg.getTargetId();
        if (targetId == null) return;

        String roomCode = sessionRooms.get(session.getId());
        if (roomCode == null) return;

        Map<String, WebSocketSession> room = roomSessions.get(roomCode);
        if (room == null) return;

        WebSocketSession target = room.get(targetId);
        if (target != null && target.isOpen()) {
            // Always stamp the REAL sender — clients can't impersonate each other
            msg.setSenderId(session.getId());
            msg.setSenderName(userName(session));
            sendQuietly(target, msg);
        } else {
            log.warn("forwardToTarget: target {} not found or closed", targetId);
        }
    }

    // ── CHAT ──────────────────────────────────────────────────────────────

    private void handleChat(WebSocketSession session, SignalingMessage msg) {
        String roomCode = sessionRooms.get(session.getId());
        if (roomCode == null) return;                       // must JOIN first

        String text = msg.getPayload() == null ? "" : msg.getPayload().toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_CHAT_LENGTH) text = text.substring(0, MAX_CHAT_LENGTH);

        String name = userName(session);
        Long uid = userId(session);

        msg.setSenderId(session.getId());
        msg.setSenderName(name);                            // server-side name, not client-supplied
        msg.setMeetingCode(roomCode);
        msg.setPayload(text);
        broadcastToRoomAll(roomCode, msg);

        final String content = text;
        try {
            meetingRepository.findByMeetingCode(roomCode).ifPresent(meeting -> {
                ChatMessage cm = new ChatMessage();
                cm.setMeeting(meeting);
                cm.setSenderId(uid);                        // sender_id is NOT NULL in the schema
                cm.setSenderName(name);
                cm.setContent(content);
                chatMessageRepository.save(cm);
            });
        } catch (Exception e) {
            log.error("Failed to persist chat message: {}", e.getMessage());
        }
    }

    // ── MEDIA_STATE (mic / camera / screen-share status) ──────────────────

    /**
     * A client announces its own state:  payload = { audio, video, screen }
     *   audio  : "on" | "muted" | "unavailable"   (unavailable = no mic / permission denied)
     *   video  : "on" | "off"   | "unavailable"
     *   screen : true | false                      (currently sharing its screen)
     * The state is sanitised, cached (for people who join later) and relayed to the rest of the room.
     */
    private void handleMediaState(WebSocketSession session, SignalingMessage msg) {
        String roomCode = sessionRooms.get(session.getId());
        if (roomCode == null) return;                       // must JOIN first

        Map<String, Object> state = sanitizeMediaState(msg.getPayload());
        mediaStates.put(session.getId(), state);

        SignalingMessage out = new SignalingMessage();
        out.setType("MEDIA_STATE");
        out.setSenderId(session.getId());                   // real sender, never client-supplied
        out.setSenderName(userName(session));
        out.setMeetingCode(roomCode);
        out.setPayload(state);
        broadcastToRoom(roomCode, session.getId(), out);
    }

    private Map<String, Object> sanitizeMediaState(Object raw) {
        String audio = "on";
        String video = "on";
        boolean screen = false;
        if (raw instanceof Map<?, ?> m) {
            Object a = m.get("audio");
            Object v = m.get("video");
            if ("muted".equals(a) || "unavailable".equals(a)) audio = (String) a;
            if ("off".equals(v) || "unavailable".equals(v))   video = (String) v;
            screen = Boolean.TRUE.equals(m.get("screen"));
        }
        Map<String, Object> state = new HashMap<>();
        state.put("audio", audio);
        state.put("video", video);
        state.put("screen", screen);
        return state;
    }

    private Map<String, Object> defaultMediaState() {
        return sanitizeMediaState(null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Long userId(WebSocketSession s) {
        Object v = s.getAttributes().get("userId");
        return (v instanceof Long l) ? l : null;
    }

    private String userName(WebSocketSession s) {
        Object v = s.getAttributes().get("userName");
        return v != null ? v.toString() : "Guest";
    }

    /** Per-session lock: one slow client no longer blocks sends to everyone else. */
    private void sendMessage(WebSocketSession session, SignalingMessage msg) throws IOException {
        if (session != null && session.isOpen()) {
            String json = objectMapper.writeValueAsString(msg);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }

    private void sendQuietly(WebSocketSession session, SignalingMessage msg) {
        try {
            sendMessage(session, msg);
        } catch (IOException e) {
            log.error("Send error to {}: {}", session.getId(), e.getMessage());
        }
    }

    private void broadcastToRoom(String roomCode, String excludeId, SignalingMessage msg) {
        Map<String, WebSocketSession> room = roomSessions.get(roomCode);
        if (room == null) return;
        room.forEach((id, s) -> { if (!id.equals(excludeId)) sendQuietly(s, msg); });
    }

    private void broadcastToRoomAll(String roomCode, SignalingMessage msg) {
        Map<String, WebSocketSession> room = roomSessions.get(roomCode);
        if (room == null) return;
        room.values().forEach(s -> sendQuietly(s, msg));
    }
}
