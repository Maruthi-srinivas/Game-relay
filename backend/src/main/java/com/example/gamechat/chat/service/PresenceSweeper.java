package com.example.gamechat.chat.service;

import com.example.gamechat.chat.bus.ChatEvent;
import com.example.gamechat.chat.bus.ChatEventKind;
import com.example.gamechat.chat.bus.ChatEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PresenceSweeper {

    private final PresenceService presenceService;
    private final ChatEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public PresenceSweeper(
            PresenceService presenceService,
            ChatEventPublisher publisher,
            ObjectMapper objectMapper
    ) {
        this.presenceService = presenceService;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.chat.presence-sweep-ms}")
    public void sweep() {
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
