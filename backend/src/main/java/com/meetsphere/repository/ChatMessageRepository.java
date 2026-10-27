// backend/src/main/java/com/meetsphere/repository/ChatMessageRepository.java
package com.meetsphere.repository;

import com.meetsphere.model.ChatMessage;
import com.meetsphere.model.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ChatMessageRepository — persists and retrieves chat history.
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // Load chat history when a user joins a meeting
    List<ChatMessage> findByMeetingOrderBySentAtAsc(Meeting meeting);

    /** Latest 100 messages (newest first) — callers reverse for display. */
    List<ChatMessage> findTop100ByMeetingOrderBySentAtDesc(Meeting meeting);
}
