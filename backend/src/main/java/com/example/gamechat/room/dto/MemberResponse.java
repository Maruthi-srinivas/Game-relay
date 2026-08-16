package com.example.gamechat.room.dto;

import java.time.Instant;
import java.util.UUID;

public record MemberResponse(
        UUID userId,
        String username,
        String role,
        Instant joinedAt
) {
}
