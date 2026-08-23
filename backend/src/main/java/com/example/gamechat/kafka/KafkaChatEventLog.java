package com.example.gamechat.kafka;

import com.example.gamechat.chat.entity.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaChatEventLog implements ChatEventLog {

    private static final Logger log = LoggerFactory.getLogger(KafkaChatEventLog.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaChatEventLog(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void messagePersisted(Message message) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("messageId", message.getId().toString());
        body.put("roomId", message.getRoomId().toString());
        body.put("senderId", message.getSenderId().toString());
        body.put("sequenceNumber", message.getSequenceNumber());
        body.put("timestamp", message.getCreatedAt().toString());
        send("chat.message.persisted", message.getRoomId().toString(), body);
    }

    @Override
    public void userJoined(UUID roomId, UUID userId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("roomId", roomId.toString());
        body.put("userId", userId.toString());
        send("chat.user.joined", roomId.toString(), body);
    }

    @Override
    public void userLeft(UUID roomId, UUID userId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("roomId", roomId.toString());
        body.put("userId", userId.toString());
        send("chat.user.left", roomId.toString(), body);
    }

    @Override
    public void moderation(String action, UUID roomId, UUID actorId, UUID messageId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("action", action);
        body.put("roomId", roomId.toString());
        body.put("actorId", actorId.toString());
        if (messageId != null) {
            body.put("messageId", messageId.toString());
        }
        send("chat.moderation", roomId.toString(), body);
    }

    private void send(String topic, String key, ObjectNode body) {
        Runnable publish = () -> {
            try {
                kafkaTemplate.send(topic, key, objectMapper.writeValueAsString(body));
            } catch (Exception ex) {
                log.warn("Failed to publish Kafka event topic={}", topic, ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
            return;
        }
        publish.run();
    }
}
