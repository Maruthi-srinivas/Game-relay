package com.example.gamechat.auth.security;

import java.util.UUID;

public record UserPrincipal(UUID userId, String username) {
}
