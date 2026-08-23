package com.example.gamechat.chat.bus;

import com.example.gamechat.chat.websocket.LocalFrameSender;
import com.example.gamechat.chat.websocket.SessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class ChatEventListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ChatEventListener.class);

    private final ObjectMapper objectMapper;
    private final LocalFrameSender localFrameSender;
    private final SessionRegistry sessionRegistry;

    public ChatEventListener(
            ObjectMapper objectMapper,
            LocalFrameSender localFrameSender,
            SessionRegistry sessionRegistry
    ) {
        this.objectMapper = objectMapper;
        this.localFrameSender = localFrameSender;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        ChatEvent event;
        try {
            event = objectMapper.readValue(payload, ChatEvent.class);
        } catch (Exception ex) {
            log.warn("Ignoring malformed chat event");
            log.debug("Malformed chat event payload", ex);
            return;
        }
        if (event == null || event.kind() == null || event.roomId() == null || event.body() == null) {
            log.warn("Ignoring incomplete chat event");
            return;
        }
        try {
            switch (event.kind()) {
                case MESSAGE, PRESENCE, TYPING, USER_JOINED, USER_LEFT, MESSAGE_DELETED, MESSAGE_EDITED, REACTION, DELIVERY, READ -> localFrameSender.sendToRoom(
                        event.roomId(),
                        objectMapper.writeValueAsString(event.body()),
                        event.excludeSessionId()
                );
                case DROP_USER -> {
                    String userId = event.body().path("userId").asText(null);
                    if (userId == null || userId.isBlank()) {
                        return;
                    }
                    sessionRegistry.removeUserFromRoom(UUID.fromString(userId), event.roomId());
                }
            }
        } catch (Exception ex) {
            log.error("Failed to apply chat event kind={} room={}", event.kind(), event.roomId(), ex);
        }
    }
}
