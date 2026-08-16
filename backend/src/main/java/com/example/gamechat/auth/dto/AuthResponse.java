package com.example.gamechat.auth.dto;

import java.util.UUID;

public record AuthResponse(String token, UUID userId, String username) {
}
