package com.example.gamechat.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReportRequest(
        UUID targetUserId,
        UUID messageId,
        @NotBlank @Size(max = 500) String reason
) {
}
