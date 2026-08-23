package com.example.gamechat.kafka;

import com.example.gamechat.chat.entity.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpChatEventLog implements ChatEventLog {

    @Override
    public void messagePersisted(Message message) {
        // Kafka disabled
    }

    @Override
    public void userJoined(UUID roomId, UUID userId) {
        // Kafka disabled
    }

    @Override
    public void userLeft(UUID roomId, UUID userId) {
        // Kafka disabled
    }

    @Override
    public void moderation(String action, UUID roomId, UUID actorId, UUID messageId) {
        // Kafka disabled
    }
}
