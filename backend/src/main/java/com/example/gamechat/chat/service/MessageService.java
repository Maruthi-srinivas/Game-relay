package com.example.gamechat.chat.service;

import com.example.gamechat.chat.dto.MessageResponse;
import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.chat.repository.MessageRepository;
import com.example.gamechat.common.dto.PageResponse;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.room.service.RoomService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final RoomService roomService;
    private final int maxMessageLength;

    public MessageService(
            MessageRepository messageRepository,
            RoomService roomService,
            @Value("${app.chat.max-message-length}") int maxMessageLength
    ) {
        this.messageRepository = messageRepository;
        this.roomService = roomService;
        this.maxMessageLength = maxMessageLength;
    }

    @Transactional
    public Message save(UUID roomId, UUID senderId, String content) {
        String normalized = validateContent(content);
        Message message = new Message();
        message.setRoomId(roomId);
        message.setSenderId(senderId);
        message.setContent(normalized);
        return messageRepository.save(message);
    }

    @Transactional(readOnly = true)
    public PageResponse<MessageResponse> history(UUID roomId, UUID userId, int page, int size) {
        roomService.requireMember(roomId, userId);
        int safePage = Math.max(page, 0);
        int safeSize = size < 1 ? 20 : Math.min(size, 100);
        Page<Message> result = messageRepository.findByRoomIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                roomId,
                PageRequest.of(safePage, safeSize)
        );
        return new PageResponse<>(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    public String validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("Message content must not be blank");
        }
        String trimmed = content.trim();
        if (trimmed.length() > maxMessageLength) {
            throw ApiException.badRequest("Message exceeds max length of " + maxMessageLength);
        }
        return trimmed;
    }

    public MessageResponse toResponse(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getRoomId(),
                message.getSenderId(),
                message.getContent(),
                message.getCreatedAt()
        );
    }
}
