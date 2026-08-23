package com.example.gamechat.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageResponse(
        UUID messageId,
        UUID roomId,
        UUID senderId,
        String content,
        Instant timestamp,
        long sequenceNumber,
        String requestId,
        Instant editedAt,
        List<AttachmentResponse> attachments,
        List<ReactionResponse> reactions
) {
    public MessageResponse(
            UUID messageId,
            UUID roomId,
            UUID senderId,
            String content,
            Instant timestamp,
            long sequenceNumber
    ) {
        this(messageId, roomId, senderId, content, timestamp, sequenceNumber, null, null, List.of(), List.of());
    }
}
