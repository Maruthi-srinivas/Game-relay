package com.example.gamechat.chat.repository;

import com.example.gamechat.chat.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Page<Message> findByRoomIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID roomId, Pageable pageable);

    Page<Message> findByRoomIdAndDeletedAtIsNullAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            UUID roomId,
            long sequenceNumber,
            Pageable pageable
    );

    Optional<Message> findByRoomIdAndSenderIdAndRequestId(UUID roomId, UUID senderId, String requestId);

    @Query(
            value = """
                    SELECT * FROM messages
                    WHERE room_id = :roomId
                      AND deleted_at IS NULL
                      AND content_tsv @@ plainto_tsquery('simple', :query)
                    ORDER BY created_at DESC
                    """,
            countQuery = """
                    SELECT count(*) FROM messages
                    WHERE room_id = :roomId
                      AND deleted_at IS NULL
                      AND content_tsv @@ plainto_tsquery('simple', :query)
                    """,
            nativeQuery = true
    )
    Page<Message> search(@Param("roomId") UUID roomId, @Param("query") String query, Pageable pageable);
}
