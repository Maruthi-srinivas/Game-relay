package com.example.gamechat.chat.controller;

import com.example.gamechat.auth.security.UserPrincipal;
import com.example.gamechat.chat.dto.MessageResponse;
import com.example.gamechat.chat.service.MessageService;
import com.example.gamechat.common.dto.PageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping
    public PageResponse<MessageResponse> history(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return messageService.history(roomId, principal.userId(), page, size);
    }
}
