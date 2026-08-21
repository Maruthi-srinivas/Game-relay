package com.example.gamechat.chat.bus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ChatEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatEventPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ChatEventPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void publishAfterCommit(ChatEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
            return;
        }
        publish(event);
    }

    public void publish(ChatEvent event) {
        try {
            redis.convertAndSend(RedisConfig.CHANNEL, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize chat event kind={}", event.kind(), ex);
        } catch (RuntimeException ex) {
            log.error("Failed to publish chat event kind={} room={}", event.kind(), event.roomId(), ex);
        }
    }
}
