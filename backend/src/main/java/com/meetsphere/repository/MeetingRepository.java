// backend/src/main/java/com/meetsphere/repository/MeetingRepository.java
package com.meetsphere.repository;

import com.meetsphere.model.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * MeetingRepository — database access for Meeting entities.
 */
@Repository
public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    // Fetch meeting by its share code (used in join flow)
    Optional<Meeting> findByMeetingCode(String meetingCode);

    // Check uniqueness before creating
    boolean existsByMeetingCode(String meetingCode);

    // Meeting + its host in ONE query (host is LAZY) — used when sending invitation emails,
    // so no database transaction has to stay open while we talk to the SMTP server.
    @Query("SELECT m FROM Meeting m JOIN FETCH m.host WHERE m.meetingCode = :code")
    Optional<Meeting> findByMeetingCodeWithHost(@Param("code") String code);
}
