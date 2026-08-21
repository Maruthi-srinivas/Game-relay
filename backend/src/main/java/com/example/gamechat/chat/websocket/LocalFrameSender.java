package com.example.gamechat.chat.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.UUID;

@Component
public class LocalFrameSender {

    private static final Logger log = LoggerFactory.getLogger(LocalFrameSender.class);

    private final SessionRegistry sessionRegistry;

    public LocalFrameSender(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    public void sendToRoom(UUID roomId, String json, String excludeSessionId) {
        for (WebSocketSession session : sessionRegistry.sessionsInRoom(roomId)) {
            if (excludeSessionId != null && excludeSessionId.equals(session.getId())) {
                continue;
            }
            try {
                SessionRegistry.send(session, json);
            } catch (IOException ex) {
                log.warn("Failed to send frame to session {}", session.getId(), ex);
            }
        }
    }
}
