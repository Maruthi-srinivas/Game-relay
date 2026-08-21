package com.example.gamechat.chat.repository;

import com.example.gamechat.chat.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Page<Message> findByRoomIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID roomId, Pageable pageable);

    Page<Message> findByRoomIdAndDeletedAtIsNullAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            UUID roomId,
            long sequenceNumber,
            Pageable pageable
    );
}
