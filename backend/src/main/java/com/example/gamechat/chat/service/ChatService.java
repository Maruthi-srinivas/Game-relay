package com.example.gamechat.chat.service;

import com.example.gamechat.chat.dto.SyncBatch;
import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.kafka.ChatEventLog;
import com.example.gamechat.room.service.RoomService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ChatService {

    private final RoomService roomService;
    private final MessageService messageService;
    private final RateLimitService rateLimitService;
    private final ChatEventLog chatEventLog;
    private final ChatMetrics chatMetrics;

    public ChatService(
            RoomService roomService,
            MessageService messageService,
            RateLimitService rateLimitService,
            ChatEventLog chatEventLog,
            ChatMetrics chatMetrics
    ) {
        this.roomService = roomService;
        this.messageService = messageService;
        this.rateLimitService = rateLimitService;
        this.chatEventLog = chatEventLog;
        this.chatMetrics = chatMetrics;
    }

    @Transactional
    public Message sendMessage(UUID userId, UUID roomId, String content) {
        roomService.requireMember(roomId, userId);
        if (roomService.isMuted(roomId, userId)) {
            throw ApiException.forbidden("You are muted in this room");
        }
        rateLimitService.checkSend(userId);
        Message saved = messageService.save(roomId, userId, content);
        chatEventLog.messagePersisted(saved);
        chatMetrics.recordMessageSent();
        return saved;
    }

    public void requireMember(UUID roomId, UUID userId) {
        roomService.requireMember(roomId, userId);
    }

    @Transactional(readOnly = true)
    public SyncBatch sync(UUID userId, UUID roomId, long afterSequence) {
        roomService.requireMember(roomId, userId);
        return messageService.syncAfter(roomId, afterSequence);
    }

    @Transactional
    public Message deleteMessage(UUID userId, UUID roomId, UUID messageId) {
        Message deleted = messageService.softDelete(roomId, userId, messageId);
        chatEventLog.moderation("MESSAGE_DELETED", roomId, userId, messageId);
        return deleted;
    }

    @Transactional
    public Message editMessage(UUID userId, UUID roomId, UUID messageId, String content) {
        return messageService.edit(roomId, userId, messageId, content);
    }
}
