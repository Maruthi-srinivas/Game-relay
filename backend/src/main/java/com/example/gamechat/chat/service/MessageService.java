package com.example.gamechat.chat.service;

import com.example.gamechat.chat.dto.AttachmentResponse;
import com.example.gamechat.chat.dto.MessageResponse;
import com.example.gamechat.chat.dto.ReactionResponse;
import com.example.gamechat.chat.dto.SyncBatch;
import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.chat.entity.MessageAttachment;
import com.example.gamechat.chat.entity.MessageReaction;
import com.example.gamechat.chat.entity.MessageReactionId;
import com.example.gamechat.chat.repository.MessageAttachmentRepository;
import com.example.gamechat.chat.repository.MessageReactionRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final RoomService roomService;
    private final MessageReactionRepository reactionRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final int maxMessageLength;
    private final int syncBatchSize;

    public MessageService(
            MessageRepository messageRepository,
            RoomRepository roomRepository,
            RoomService roomService,
            MessageReactionRepository reactionRepository,
            MessageAttachmentRepository attachmentRepository,
            @Value("${app.chat.max-message-length}") int maxMessageLength,
            @Value("${app.chat.sync-batch-size}") int syncBatchSize
    ) {
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.roomService = roomService;
        this.reactionRepository = reactionRepository;
        this.attachmentRepository = attachmentRepository;
        this.maxMessageLength = maxMessageLength;
        this.syncBatchSize = syncBatchSize;
    }

    @Transactional
    public Message save(UUID roomId, UUID senderId, String content) {
        return save(roomId, senderId, content, null, false);
    }

    @Transactional
    public Message save(UUID roomId, UUID senderId, String content, String requestId, boolean allowBlank) {
        if (requestId != null && !requestId.isBlank()) {
            var existing = messageRepository.findByRoomIdAndSenderIdAndRequestId(roomId, senderId, requestId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String normalized = allowBlank ? validateContentAllowBlank(content) : validateContent(content);
        Room room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> ApiException.notFound("Room not found"));
        long next = room.getLastSequence() + 1;
        room.setLastSequence(next);
        Message message = new Message();
        message.setRoomId(roomId);
        message.setSenderId(senderId);
        message.setContent(normalized);
        message.setRequestId(requestId == null || requestId.isBlank() ? null : requestId.trim());
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
        return toPage(result);
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
                toResponses(result.getContent()),
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
        List<Message> slice = truncated ? rows.subList(0, limit) : rows;
        return new SyncBatch(toResponses(slice), truncated);
    }

    @Transactional(readOnly = true)
    public PageResponse<MessageResponse> search(UUID roomId, UUID userId, String query, int size) {
        roomService.requireMember(roomId, userId);
        if (query == null || query.isBlank()) {
            throw ApiException.badRequest("Search query is required");
        }
        int safeSize = size < 1 ? 20 : Math.min(size, 50);
        Page<Message> result = messageRepository.search(roomId, query.trim(), PageRequest.of(0, safeSize));
        return new PageResponse<>(
                toResponses(result.getContent()),
                0,
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    public String validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("Message content must not be blank");
        }
        return validateLength(content.trim());
    }

    public String validateContentAllowBlank(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return validateLength(content.trim());
    }

    private String validateLength(String trimmed) {
        if (trimmed.length() > maxMessageLength) {
            throw ApiException.badRequest("Message exceeds max length of " + maxMessageLength);
        }
        return trimmed;
    }

    @Transactional
    public Message softDelete(UUID roomId, UUID userId, UUID messageId) {
        Message message = requireOwnMessage(roomId, userId, messageId);
        message.setDeletedAt(Instant.now());
        return message;
    }

    @Transactional
    public Message edit(UUID roomId, UUID userId, UUID messageId, String content) {
        Message message = requireOwnMessage(roomId, userId, messageId);
        if (message.getCreatedAt().isBefore(Instant.now().minusSeconds(300))) {
            throw ApiException.forbidden("Edit window has expired");
        }
        message.setContent(validateContent(content));
        message.setEditedAt(Instant.now());
        return message;
    }

    @Transactional
    public MessageReaction addReaction(UUID roomId, UUID userId, UUID messageId, String emoji) {
        roomService.requireMember(roomId, userId);
        String normalized = requireEmoji(emoji);
        Message message = requireVisibleMessage(roomId, messageId);
        MessageReactionId id = new MessageReactionId(message.getId(), userId, normalized);
        return reactionRepository.findById(id).orElseGet(() -> {
            MessageReaction reaction = new MessageReaction();
            reaction.setId(id);
            return reactionRepository.save(reaction);
        });
    }

    @Transactional
    public void removeReaction(UUID roomId, UUID userId, UUID messageId, String emoji) {
        roomService.requireMember(roomId, userId);
        reactionRepository.deleteById(new MessageReactionId(messageId, userId, requireEmoji(emoji)));
    }

    public Message requireVisibleMessage(UUID roomId, UUID messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> ApiException.notFound("Message not found"));
        if (!message.getRoomId().equals(roomId) || message.getDeletedAt() != null) {
            throw ApiException.notFound("Message not found");
        }
        return message;
    }

    private Message requireOwnMessage(UUID roomId, UUID userId, UUID messageId) {
        roomService.requireMember(roomId, userId);
        Message message = requireVisibleMessage(roomId, messageId);
        if (!message.getSenderId().equals(userId) && !roomService.isModerator(roomId, userId)) {
            throw ApiException.forbidden("Cannot modify this message");
        }
        return message;
    }

    private static String requireEmoji(String emoji) {
        if (emoji == null || emoji.isBlank() || emoji.length() > 32) {
            throw ApiException.badRequest("Invalid reaction emoji");
        }
        return emoji.trim();
    }

    public MessageResponse toResponse(Message message) {
        return toResponses(List.of(message)).getFirst();
    }

    private PageResponse<MessageResponse> toPage(Page<Message> result) {
        return new PageResponse<>(
                toResponses(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    private List<MessageResponse> toResponses(List<Message> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = messages.stream().map(Message::getId).toList();
        Map<UUID, List<MessageAttachment>> attachments = attachmentRepository.findByMessageIdIn(ids).stream()
                .collect(Collectors.groupingBy(MessageAttachment::getMessageId));
        Map<UUID, List<MessageReaction>> reactions = reactionRepository.findByIdMessageIdIn(ids).stream()
                .collect(Collectors.groupingBy(item -> item.getId().getMessageId()));
        List<MessageResponse> out = new ArrayList<>();
        for (Message message : messages) {
            Map<String, List<UUID>> byEmoji = new LinkedHashMap<>();
            for (MessageReaction reaction : reactions.getOrDefault(message.getId(), List.of())) {
                byEmoji.computeIfAbsent(reaction.getId().getEmoji(), key -> new ArrayList<>())
                        .add(reaction.getId().getUserId());
            }
            out.add(new MessageResponse(
                    message.getId(),
                    message.getRoomId(),
                    message.getSenderId(),
                    message.getContent(),
                    message.getCreatedAt(),
                    message.getSequenceNumber(),
                    message.getRequestId(),
                    message.getEditedAt(),
                    attachments.getOrDefault(message.getId(), List.of()).stream()
                            .map(item -> new AttachmentResponse(
                                    item.getId(),
                                    item.getContentType(),
                                    item.getOriginalName(),
                                    item.getSizeBytes()
                            ))
                            .toList(),
                    byEmoji.entrySet().stream()
                            .map(entry -> new ReactionResponse(entry.getKey(), entry.getValue().size(), List.copyOf(entry.getValue())))
                            .toList()
            ));
        }
        return out;
    }
}
