package com.example.gamechat.room.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreatePrivateRequest(@NotNull UUID userId) {
}
