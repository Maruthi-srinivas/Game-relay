package com.example.gamechat.room.dto;

import java.time.Instant;
import java.util.UUID;

public record RoomResponse(
        UUID id,
        String name,
        String type,
        UUID ownerId,
        int maxMembers,
        Instant createdAt,
        Instant updatedAt,
        long unreadCount
) {
}
