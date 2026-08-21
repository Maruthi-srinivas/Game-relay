package com.example.gamechat.chat.bus;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record ChatEvent(
        ChatEventKind kind,
        UUID roomId,
        String excludeSessionId,
        JsonNode body
) {
}
