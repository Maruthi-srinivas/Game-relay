package com.example.gamechat.chat.service;

import com.example.gamechat.chat.bus.ChatEvent;
import com.example.gamechat.chat.bus.ChatEventKind;
import com.example.gamechat.chat.bus.ChatEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.gamechat.chat.websocket.SessionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class PresenceSweeper {

    private final PresenceService presenceService;
    private final ChatEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final SessionRegistry sessionRegistry;
    private final long awayIdleMs;

    public PresenceSweeper(
            PresenceService presenceService,
            ChatEventPublisher publisher,
            ObjectMapper objectMapper,
            SessionRegistry sessionRegistry,
            @Value("${app.chat.away-idle-ms:300000}") long awayIdleMs
    ) {
        this.presenceService = presenceService;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.sessionRegistry = sessionRegistry;
        this.awayIdleMs = awayIdleMs;
    }

    @Scheduled(fixedDelayString = "${app.chat.presence-sweep-ms}")
    public void sweep() {
        Instant cutoff = Instant.now().minusMillis(Math.max(awayIdleMs, 1000));
        for (SessionRegistry.IdleUser idle : sessionRegistry.idleAuthenticatedUsers(cutoff)) {
            if (!"ONLINE".equals(presenceService.currentStatus(idle.userId()))) {
                continue;
            }
            presenceService.setStatus(idle.userId(), idle.username(), "AWAY");
            for (UUID roomId : presenceService.roomsOf(idle.userId())) {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("type", "PRESENCE");
                body.put("roomId", roomId.toString());
                body.put("userId", idle.userId().toString());
                body.put("username", idle.username());
                body.put("status", "AWAY");
                publisher.publish(new ChatEvent(ChatEventKind.PRESENCE, roomId, null, body));
            }
        }
        for (PresenceService.ExpiredPresence expired : presenceService.reapExpired()) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("type", "PRESENCE");
            body.put("roomId", expired.roomId().toString());
            body.put("userId", expired.userId().toString());
            body.put("username", expired.username());
            body.put("status", "OFFLINE");
            publisher.publish(new ChatEvent(ChatEventKind.PRESENCE, expired.roomId(), null, body));
        }
    }
}
