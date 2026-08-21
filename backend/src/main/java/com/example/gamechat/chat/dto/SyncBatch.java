package com.example.gamechat.chat.dto;

import java.util.List;

public record SyncBatch(
        List<MessageResponse> messages,
        boolean truncated
) {
}
