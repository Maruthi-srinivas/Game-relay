package com.example.gamechat.kafka;

import com.example.gamechat.chat.entity.Message;

import java.util.UUID;

public interface ChatEventLog {

    void messagePersisted(Message message);

    void userJoined(UUID roomId, UUID userId);

    void userLeft(UUID roomId, UUID userId);

    void moderation(String action, UUID roomId, UUID actorId, UUID messageId);
}
