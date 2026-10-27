// backend/src/main/java/com/meetsphere/model/Meeting.java
package com.meetsphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Meeting entity — maps to the `meetings` table.
 * Each meeting has a globally unique code used to generate share links.
 */
@Entity
@Table(name = "meetings")
@Data
@NoArgsConstructor
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Human-readable unique code, e.g. "abc-defg-hij"
    @Column(name = "meeting_code", nullable = false, unique = true, length = 20)
    private String meetingCode;

    @Column(nullable = false, length = 200)
    private String title = "MeetSphere Meeting";

    // The user who created this meeting room
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", nullable = false)
    private User host;

    // When false, the room is effectively closed
    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
