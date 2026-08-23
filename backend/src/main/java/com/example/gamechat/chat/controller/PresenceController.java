package com.example.gamechat.chat.controller;

import com.example.gamechat.auth.security.UserPrincipal;
import com.example.gamechat.chat.service.PresenceService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class PresenceController {

    public record PresenceResponse(UUID userId, String status, Instant lastSeenAt) {
    }

    private final PresenceService presenceService;

    public PresenceController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @GetMapping("/{userId}/presence")
    public PresenceResponse presence(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userId
    ) {
        return new PresenceResponse(userId, presenceService.currentStatus(userId), presenceService.lastSeen(userId));
    }
}
