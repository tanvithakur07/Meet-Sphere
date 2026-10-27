// backend/src/main/java/com/meetsphere/repository/MeetingParticipantRepository.java
package com.meetsphere.repository;

import com.meetsphere.model.Meeting;
import com.meetsphere.model.MeetingParticipant;
import com.meetsphere.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MeetingParticipantRepository — tracks meeting attendance.
 */
@Repository
public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, Long> {

    // Get all current (non-left) participants of a meeting
    @Query("SELECT mp FROM MeetingParticipant mp " +
           "JOIN FETCH mp.user " +
           "WHERE mp.meeting = :meeting AND mp.leftAt IS NULL")
    List<MeetingParticipant> findActiveByMeeting(Meeting meeting);

    // Check if a user is already in a meeting (prevent duplicates)
    Optional<MeetingParticipant> findByMeetingAndUserAndLeftAtIsNull(Meeting meeting, User user);
}
