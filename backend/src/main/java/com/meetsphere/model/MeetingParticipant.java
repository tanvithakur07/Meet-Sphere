// backend/src/main/java/com/meetsphere/model/MeetingParticipant.java
package com.meetsphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * MeetingParticipant — join table tracking which users are in which meetings.
 * leftAt is null while the user is still present.
 */
@Entity
@Table(name = "meeting_participants")
@Data
@NoArgsConstructor
public class MeetingParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt = LocalDateTime.now();

    // Set when user clicks "Leave Meeting"
    @Column(name = "left_at")
    private LocalDateTime leftAt;
}
