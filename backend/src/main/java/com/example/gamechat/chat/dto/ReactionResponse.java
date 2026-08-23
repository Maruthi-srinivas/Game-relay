package com.example.gamechat.chat.dto;

import java.util.UUID;

public record ReactionResponse(
        String emoji,
        long count,
        java.util.List<UUID> userIds
) {
}
