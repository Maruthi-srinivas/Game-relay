package com.example.gamechat.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID messageId,
        UUID roomId,
        UUID senderId,
        String content,
        Instant timestamp,
        long sequenceNumber
) {
}
