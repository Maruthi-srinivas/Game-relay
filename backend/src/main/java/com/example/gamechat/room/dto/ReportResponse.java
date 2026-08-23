package com.example.gamechat.room.dto;

import java.time.Instant;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        UUID roomId,
        UUID reporterId,
        UUID targetUserId,
        UUID messageId,
        String reason,
        String status,
        Instant createdAt,
        Instant resolvedAt
) {
    public ReportResponse(UUID id) {
        this(id, null, null, null, null, null, "OPEN", null, null);
    }
}
