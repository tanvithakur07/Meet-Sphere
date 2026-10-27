// backend/src/main/java/com/meetsphere/model/ChatMessage.java
package com.meetsphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ChatMessage — persists text messages sent during a meeting.
 * Also used as the WebSocket STOMP payload DTO (dual-purpose).
 */
@Entity
@Table(name = "chat_messages")
@Data
@NoArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id")
    private Meeting meeting;

    // We store sender details here for fast retrieval
    @Column(name = "sender_id")
    private Long senderId;

    @Column(name = "sender_name", length = 100)
    private String senderName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "sent_at")
    private LocalDateTime sentAt = LocalDateTime.now();

    // ── Non-persisted fields used as WebSocket DTO ───────────
    // meetingCode is used by WebSocket broker; not stored in DB
    @Transient
    private String meetingCode;

    // Message type: CHAT | JOIN | LEAVE | OFFER | ANSWER | ICE_CANDIDATE
    @Transient
    private String type;

    // WebRTC signaling payload (SDP or ICE candidate JSON)
    @Transient
    private Object payload;

    // Target peer userId (for point-to-point signaling)
    @Transient
    private String targetId;

    // Sender's WebSocket session ID (set by server)
    @Transient
    private String senderId2; // Used as String peerId in signaling
}
