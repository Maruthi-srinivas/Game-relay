package com.example.gamechat.chat.repository;

import com.example.gamechat.chat.entity.MessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, UUID> {

    List<MessageAttachment> findByMessageIdIn(Collection<UUID> messageIds);

    List<MessageAttachment> findByMessageId(UUID messageId);
}
