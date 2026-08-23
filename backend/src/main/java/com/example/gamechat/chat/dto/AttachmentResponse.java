package com.example.gamechat.chat.dto;

import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        String contentType,
        String originalName,
        long sizeBytes
) {
}
