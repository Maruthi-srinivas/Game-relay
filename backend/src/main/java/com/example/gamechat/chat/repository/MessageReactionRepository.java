package com.example.gamechat.chat.repository;

import com.example.gamechat.chat.entity.MessageReaction;
import com.example.gamechat.chat.entity.MessageReactionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, MessageReactionId> {

    List<MessageReaction> findByIdMessageIdIn(Collection<UUID> messageIds);

    List<MessageReaction> findByIdMessageId(UUID messageId);
}
