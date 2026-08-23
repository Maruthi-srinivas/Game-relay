package com.example.gamechat.auth.security;

import java.util.UUID;

public record UserPrincipal(UUID userId, String username, String jti) {

    public UserPrincipal(UUID userId, String username) {
        this(userId, username, null);
    }
}
