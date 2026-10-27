// backend/src/main/java/com/meetsphere/websocket/SignalingMessage.java
package com.meetsphere.websocket;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SignalingMessage — the universal DTO for all WebSocket messages.
 *
 * Message types:
 *   JOIN           → User enters a meeting room
 *   LEAVE          → User exits a meeting room
 *   OFFER          → WebRTC SDP offer (caller → callee)
 *   ANSWER         → WebRTC SDP answer (callee → caller)
 *   ICE_CANDIDATE  → WebRTC ICE candidate (either peer)
 *   CHAT           → Text chat message
 *   PARTICIPANTS   → Server → client: current participant list
 *   MEDIA_STATE    → Mic / camera / screen-share status ({audio, video, screen}), relayed to the room
 */
@Data
@NoArgsConstructor
public class SignalingMessage {

    // Identifies message purpose (see above)
    private String type;

    // Meeting room identifier
    private String meetingCode;

    // Sender's session ID (auto-set by server) or userId
    private String senderId;

    // Sender's display name
    private String senderName;

    // Recipient session ID (for point-to-point WebRTC signaling)
    private String targetId;

    // Flexible payload: SDP string, ICE candidate object, or chat text
    private Object payload;
}
