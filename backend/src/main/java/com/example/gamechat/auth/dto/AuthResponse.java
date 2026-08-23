package com.example.gamechat.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.UUID;

public record AuthResponse(String accessToken, long expiresIn, UUID userId, String username) {

    @JsonIgnore
    public String token() {
        return accessToken;
    }
}
