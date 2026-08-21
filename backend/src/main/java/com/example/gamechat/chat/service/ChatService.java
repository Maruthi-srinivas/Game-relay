package com.example.gamechat.chat.service;

import com.example.gamechat.chat.dto.SyncBatch;
import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.room.service.RoomService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ChatService {

    private final RoomService roomService;
    private final MessageService messageService;

    public ChatService(RoomService roomService, MessageService messageService) {
        this.roomService = roomService;
        this.messageService = messageService;
    }

    @Transactional
    public Message sendMessage(UUID userId, UUID roomId, String content) {
        roomService.requireMember(roomId, userId);
        return messageService.save(roomId, userId, content);
    }

    public void requireMember(UUID roomId, UUID userId) {
        roomService.requireMember(roomId, userId);
    }

    @Transactional(readOnly = true)
    public SyncBatch sync(UUID userId, UUID roomId, long afterSequence) {
        roomService.requireMember(roomId, userId);
        return messageService.syncAfter(roomId, afterSequence);
    }
}
