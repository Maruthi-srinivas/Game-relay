package com.example.gamechat.room.dto;

import java.time.Instant;
import java.util.UUID;

public record InviteResponse(String code, UUID roomId, Instant expiresAt) {
}
