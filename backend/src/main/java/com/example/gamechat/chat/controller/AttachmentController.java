package com.example.gamechat.chat.controller;

import com.example.gamechat.auth.security.UserPrincipal;
import com.example.gamechat.chat.bus.ChatEvent;
import com.example.gamechat.chat.bus.ChatEventKind;
import com.example.gamechat.chat.bus.ChatEventPublisher;
import com.example.gamechat.chat.dto.MessageResponse;
import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.chat.service.AttachmentService;
import com.example.gamechat.chat.service.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final MessageService messageService;
    private final ChatEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public AttachmentController(
            AttachmentService attachmentService,
            MessageService messageService,
            ChatEventPublisher publisher,
            ObjectMapper objectMapper
    ) {
        this.attachmentService = attachmentService;
        this.messageService = messageService;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/api/rooms/{roomId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessageResponse upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption
    ) {
        Message saved = attachmentService.upload(principal.userId(), roomId, file, caption);
        MessageResponse response = messageService.toResponse(saved);
        ObjectNode payload = objectMapper.valueToTree(response);
        payload.put("type", "MESSAGE");
        publisher.publishAfterCommit(new ChatEvent(ChatEventKind.MESSAGE, roomId, null, payload));
        return response;
    }

    @GetMapping("/api/attachments/{attachmentId}")
    public ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID attachmentId
    ) {
        AttachmentService.Download download = attachmentService.download(principal.userId(), attachmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + download.originalName() + "\"")
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.sizeBytes())
                .body(new InputStreamResource(download.stream()));
    }
}
