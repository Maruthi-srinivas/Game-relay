package com.example.gamechat.chat.service;

import com.example.gamechat.chat.dto.MessageResponse;
import com.example.gamechat.chat.dto.SyncBatch;
import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.chat.repository.MessageRepository;
import com.example.gamechat.common.dto.PageResponse;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.room.entity.Room;
import com.example.gamechat.room.repository.RoomRepository;
import com.example.gamechat.room.service.RoomService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final RoomService roomService;
    private final int maxMessageLength;
    private final int syncBatchSize;

    public MessageService(
            MessageRepository messageRepository,
            RoomRepository roomRepository,
            RoomService roomService,
            @Value("${app.chat.max-message-length}") int maxMessageLength,
            @Value("${app.chat.sync-batch-size}") int syncBatchSize
    ) {
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.roomService = roomService;
        this.maxMessageLength = maxMessageLength;
        this.syncBatchSize = syncBatchSize;
    }

    @Transactional
    public Message save(UUID roomId, UUID senderId, String content) {
        String normalized = validateContent(content);
        Room room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> ApiException.notFound("Room not found"));
        long next = room.getLastSequence() + 1;
        room.setLastSequence(next);
        Message message = new Message();
        message.setRoomId(roomId);
        message.setSenderId(senderId);
        message.setContent(normalized);
        message.setSequenceNumber(next);
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

    @Transactional(readOnly = true)
    public PageResponse<MessageResponse> historyAfter(UUID roomId, UUID userId, long afterSequence, int size) {
        roomService.requireMember(roomId, userId);
        long cursor = Math.max(afterSequence, 0);
        int safeSize = size < 1 ? 20 : Math.min(size, 100);
        Page<Message> result = messageRepository
                .findByRoomIdAndDeletedAtIsNullAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                        roomId,
                        cursor,
                        PageRequest.of(0, safeSize)
                );
        return new PageResponse<>(
                result.getContent().stream().map(this::toResponse).toList(),
                0,
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public SyncBatch syncAfter(UUID roomId, long afterSequence) {
        long cursor = Math.max(afterSequence, 0);
        int limit = Math.max(syncBatchSize, 1);
        Page<Message> result = messageRepository
                .findByRoomIdAndDeletedAtIsNullAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                        roomId,
                        cursor,
                        PageRequest.of(0, limit + 1)
                );
        List<Message> rows = result.getContent();
        boolean truncated = rows.size() > limit;
        List<MessageResponse> messages = (truncated ? rows.subList(0, limit) : rows)
                .stream()
                .map(this::toResponse)
                .toList();
        return new SyncBatch(messages, truncated);
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
                message.getCreatedAt(),
                message.getSequenceNumber()
        );
    }
}
